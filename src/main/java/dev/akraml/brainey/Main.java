package dev.akraml.brainey;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.KeyCredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.instagram4j.instagram4j.IGAndroidDevice;
import com.github.instagram4j.instagram4j.IGClient;
import com.github.instagram4j.instagram4j.requests.direct.DirectThreadsBroadcastRequest;
import com.github.instagram4j.instagram4j.requests.direct.DirectThreadsBroadcastRequest.BroadcastTextPayload;
import com.github.instagram4j.instagram4j.requests.direct.DirectThreadsMarkItemSeenRequest;
import com.github.instagram4j.instagram4j.requests.users.UsersInfoRequest;
import com.github.instagram4j.instagram4j.responses.users.UserResponse;
import com.github.instagram4j.instagram4j.utils.IGChallengeUtils;
import com.github.instagram4j.instagram4j.utils.IGUtils;
import com.github.instagram4j.realtime.IGRealtimeClient;
import com.github.instagram4j.realtime.mqtt.packet.PublishPacket;
import com.github.instagram4j.realtime.utils.PacketUtil;
import com.github.instagram4j.realtime.utils.ZipUtil;

import java.io.*;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class Main {

    private static final Map<Long, String> USERNAME_CACHE = new HashMap<>();
    private static final Map<Long, String> FULL_NAME_CACHE = new HashMap<>();
    private static final List<ConversationEntry> GPT_CONVERSATIONS = new CopyOnWriteArrayList<>();
    private static final Set<Long> GPT_GENERATING = new HashSet<>();
    public static boolean STACKTRACE = true;
    private static IGClient CLIENT;
    private static IGRealtimeClient REALTIME_CLIENT;
    private static final Scanner SCANNER = new Scanner(System.in);
	private static final GroqApi GROQ_API = new GroqApi(Credentials.GROQ_API_KEY);

    public static void main(String[] args) {
        resetConversation();
        new Timer().schedule(new TimerTask() {
            @SuppressWarnings("ALL")
            @Override
            public void run() {
                try {
                    final long start = System.currentTimeMillis();
                    final File file = new File("conversations.txt");
                    if (file.exists()) file.delete();
                    file.createNewFile();
                    final List<ConversationEntry> conversationsCopied = new ArrayList<>(GPT_CONVERSATIONS);
                    conversationsCopied.remove(0);
                    conversationsCopied.remove(0);
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter("conversations.txt", true))) {
                        for (final ConversationEntry chatMessage : conversationsCopied) {
                            writer.append(chatMessage.role()).append("┃").append(chatMessage.content().replaceAll("\n", "<newline>"));
                            writer.newLine();
                        }
                    }
                    System.out.println("[System] Successfully rewrote conversations file in " + (System.currentTimeMillis() - start) + "ms.");
                } catch (Exception exception) {
                    exception.printStackTrace(System.err);
                }
            }
        }, 30000L, 30000L);
        loadConversation();
        try {
            reconnect();
            System.out.println("[System] Started the bot");
            prepareOpenAiClient();
            // Initialize command handler
            while (SCANNER.hasNext()) {
                String cmd = SCANNER.nextLine();
                if (cmd == null)
                    continue;
                if (cmd.equalsIgnoreCase("stop")) {
                    System.out.println("[System] Shutting down...");
                    try {
                        System.out.println("[Instagram] Logging out...");
                        REALTIME_CLIENT.disconnect();
                    } catch (IOException e) {
                        e.printStackTrace(System.err);
                        System.exit(1);
                    }
                    System.exit(0);
                    break;
                } else if (cmd.equalsIgnoreCase("exceptions")) {
                    STACKTRACE =! STACKTRACE;
                    System.out.println("[Command] Stack trace printing is now " + STACKTRACE);
                }
            }
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
        }
    }

    public static void reconnect() throws Exception {
        // Stopping previous connections
        if (CLIENT != null && CLIENT.isLoggedIn() && REALTIME_CLIENT != null) {
            try {
                REALTIME_CLIENT.disconnect();
            } catch (Exception exception) {
                System.out.println("[Instagram] Failed to disconnect, skipping...");
                System.out.println("[Instagram] [Exception] " + exception.getMessage());
                if (STACKTRACE) exception.printStackTrace(System.err);
            }
        }
        // Initialize client
        Callable<String> challengeCode = () -> {
            System.out.print("[Instagram] [AUTH] Challenge required! Please enter code: ");
            return SCANNER.nextLine();
        }, factorCode = () -> {
            System.out.print("[Instagram] [AUTH] 2FA required! Please enter code: ");
            return SCANNER.nextLine();
        };
        IGClient.Builder.LoginHandler challengeHandler = (client, response)
                -> IGChallengeUtils.resolveChallenge(client, response, challengeCode),
                factorHandler = (client, response)
                        -> IGChallengeUtils.resolveTwoFactor(client, response, factorCode);

        File clientFile = new File("client.ig"), cookieFile = new File("cookie.ig");

        if (clientFile.exists() && cookieFile.exists()) {
            CLIENT = IGClient.deserialize(new File("client.ig"), new File("cookie.ig"));
        } else {
            CLIENT = IGClient
                    .builder()
                    .username(Credentials.INSTAGRAM_USERNAME)
                    .password(Credentials.INSTAGRAM_PASSWORD)
                    .onChallenge(challengeHandler)
                    .onTwoFactor(factorHandler)
                    .device(IGAndroidDevice.GOOD_DEVICES[1])
                    .simulatedLogin();
            CLIENT.serialize(clientFile, cookieFile);
        }

        if (CLIENT.isLoggedIn()) {
            System.out.println("[Instagram] logged in");
        } else {
            System.out.println("[Instagram] not logged");
            return;
        }

        // Initialize real-time
        REALTIME_CLIENT = new IGRealtimeClient(CLIENT, packet -> {
            CLIENT.actions();
            try {
                if (packet instanceof final PublishPacket publishPacket) {
                    if (publishPacket.topicName.equals("146")) {
                        final String payload = PacketUtil.stringify(ZipUtil.unzip(publishPacket.getPayload()));
                        final JsonNode parentNode = IGUtils.jsonToObject(payload, JsonNode.class).get(0);
                        final JsonNode data = parentNode.get("data").get(0);
                        if (data == null) return;
                        final String thread_id = data.get("path").asText().substring(1).split("/")[2];
                        final JsonNode itemValue = IGUtils.jsonToObject(data.get("value").asText(), JsonNode.class);
                        if (itemValue == null) return;
                        if (itemValue.get("text") == null && itemValue.get("link") == null) return;
                        String message = itemValue.get("text") == null ? itemValue.get("link").get("text").asText() : itemValue.get("text").asText();
                        long userId = itemValue.get("user_id").asLong();
                        if (!USERNAME_CACHE.containsKey(userId)) {
                            final UserResponse response = CLIENT.sendRequest(new UsersInfoRequest(userId)).join();
                            final String username = response.getUser().getUsername(),
                                    fullName = response.getUser().getFull_name() == null ? username : response.getUser().getFull_name();
                            USERNAME_CACHE.put(userId, username);
                            FULL_NAME_CACHE.put(userId, fullName);
                        }
                        final String username = USERNAME_CACHE.get(userId),
                                fullName = FULL_NAME_CACHE.get(userId);
                        if (data.get("op").asText().equals("add")
                                && userId != CLIENT.getSelfProfile().getPk()
                                && message != null) {
                            // Check conversation.
//                            performOrderCheckAndCleanup();

                            final JsonNode repliedNode = itemValue.get("replied_to_message");
                            if (!message.contains("@brainey.ai")) {
                                if (repliedNode != null) {
                                    final String replyMsg = repliedNode.get("text").asText();
                                    final long whoRepliedToId = repliedNode.get("user_id").asLong();
                                    final String whoRepliedTo = USERNAME_CACHE.get(whoRepliedToId),
                                            whoRepliedToName = FULL_NAME_CACHE.get(whoRepliedToId);
                                    if (repliedNode.get("user_id").asLong() != CLIENT.getSelfProfile().getPk()) {
                                        final ConversationEntry userEntry = new ConversationEntry("user", "[" + username + "/" + fullName + "] " +
                                                "[Replying to " + whoRepliedTo + "/" + whoRepliedToName + ": " + replyMsg + "] " +
                                                message),
                                                modelEntry = new ConversationEntry("assistant", "Message has been saved");
                                        GPT_CONVERSATIONS.add(userEntry);
                                        saveMessage(userEntry);
                                        if (GPT_CONVERSATIONS.size() >= 200) {
                                            GPT_CONVERSATIONS.remove(0);
                                            GPT_CONVERSATIONS.remove(0);
                                        }
                                        return;
                                    }
                                } else {
                                    final ConversationEntry userEntry = new ConversationEntry("user", "[" + username + "/" + fullName + "] " + message),
                                            modelEntry = new ConversationEntry("assistant", "Message has been saved");
                                    GPT_CONVERSATIONS.add(userEntry);
                                    saveMessage(userEntry);
                                    if (GPT_CONVERSATIONS.size() >= 200) {
                                        GPT_CONVERSATIONS.remove(0);
                                        GPT_CONVERSATIONS.remove(0);
                                    }
                                    return;
                                }
                            }

                            // Check if it's a reply.
                            String reply = "";
                            if (repliedNode != null) {
                                // Check if who got replied is not the bot
                                if (repliedNode.get("user_id").asLong() != CLIENT.getSelfProfile().getPk()
                                        && !message.contains("@brainey.ai")) {
                                    if (repliedNode.get("user_id").asLong() != CLIENT.getSelfProfile().getPk()) return;
                                    return;
                                }
                                final String replyMsg = repliedNode.get("text").asText();
                                final long whoRepliedToId = repliedNode.get("user_id").asLong();
                                String whoRepliedTo = USERNAME_CACHE.get(whoRepliedToId),
                                        whoRepliedToName = FULL_NAME_CACHE.get(whoRepliedToId);
                                // Check if who got replied is not cached.
                                if (whoRepliedTo == null) {
                                    final UserResponse response = CLIENT.sendRequest(new UsersInfoRequest(whoRepliedToId)).join();
                                    whoRepliedTo = response.getUser().getUsername();
                                    whoRepliedToName = response.getUser().getFull_name() == null ? whoRepliedTo : response.getUser().getFull_name();
                                    USERNAME_CACHE.put(whoRepliedToId, whoRepliedTo);
                                    FULL_NAME_CACHE.put(whoRepliedToId, fullName);
                                }


                                reply = "[Replying to " + whoRepliedTo + "/" + whoRepliedToName + ": " + replyMsg + "] ";
                            }
                            if (GPT_GENERATING.contains(userId)) {
                                CLIENT.sendRequest(new DirectThreadsMarkItemSeenRequest(thread_id, itemValue.get("item_id").asText())).join();
                                CLIENT.sendRequest(new DirectThreadsBroadcastRequest(
                                        new BroadcastTextPayload("Please wait until previous message fully generates...", thread_id))).join();
                                return;
                            }

                            // Now start
                            CLIENT.sendRequest(new DirectThreadsMarkItemSeenRequest(thread_id, itemValue.get("item_id").asText())).join();
                            final ConversationEntry userMessage = new ConversationEntry("user", "[" + username + "/" + fullName + "] " + reply + message);
                            GPT_CONVERSATIONS.add(userMessage);
                            // If the size exceed the limit, then remove one message from above.
                            if (GPT_CONVERSATIONS.size() >= 75) {
                                GPT_CONVERSATIONS.remove(2);
                                GPT_CONVERSATIONS.remove(2);
                            }
                            System.out.println("[Instagram] [MESSAGE] " + username + ": " + message);
                            GPT_GENERATING.add(userId);

                            // Generate
                            try {
                                /*final JsonObject result = GptApi.generateGemini(GPT_CONVERSATIONS);
                                assert result != null;
                                final JsonObject candinates = result.getAsJsonArray("candidates").get(0).getAsJsonObject();
                                String text;
                                final String finishReason = candinates.get("finishReason").getAsString();
                                if (finishReason.equals("STOP")) {
                                    text = candinates.get("content").getAsJsonObject().getAsJsonArray("parts").get(0)
                                            .getAsJsonObject().get("text").getAsString()
                                            .replaceFirst("\\[", "").replaceFirst("]", "");
                                } else {
                                    text = "Unexpected finish reason: " + finishReason;
                                }*/
                                String text = GptApi.getResponseGroq(GPT_CONVERSATIONS, GroqModels.LLAMA3_70B);
                                final ConversationEntry chatMessage = new ConversationEntry("assistant", text);
                                GPT_CONVERSATIONS.add(chatMessage);
                                saveMessage(userMessage);
                                saveMessage(chatMessage);
                                // If the size exceed the limit, then remove one message from above.
                                if (GPT_CONVERSATIONS.size() >= 100) {
                                    GPT_CONVERSATIONS.remove(2); // user
                                    GPT_CONVERSATIONS.remove(2); // model
                                }
                                GPT_GENERATING.remove(userId);
                                System.out.println("[OpenAI] [RESPONSE] " + username + ": " + text);
                                CLIENT.sendRequest(new DirectThreadsBroadcastRequest(new BroadcastTextPayload(text, thread_id))).join();
                            } catch (Exception exception) {
                                System.out.println("[OpenAI] Failed to get response for " + username + ": " + exception.getMessage());
                                GPT_GENERATING.remove(userId);
                                CLIENT.sendRequest(new DirectThreadsMarkItemSeenRequest(thread_id, itemValue.get("item_id").asText())).join();
                                CLIENT.sendRequest(new DirectThreadsBroadcastRequest(
                                        new BroadcastTextPayload("An error occurred when generating message: "
                                                + exception.getMessage(), thread_id))).join();
                                resetConversation();
                                if (STACKTRACE) exception.printStackTrace(System.err);
                            }
                        }
                    }
                }
            } catch (Exception exception) {
                System.out.println("[Exception] Error while reading PublishPacket " + exception.getMessage());
                if (exception.getMessage() != null
                        && exception.getMessage().contains("login")
                        || exception.getMessage().contains("challenge_required")
                        || exception.getMessage().contains("An established connection was aborted by the software in your host machine")) {
                    try {
                        System.out.println("Connection failure, reconnecting after 30 seconds...");
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                try {
                                    reconnect();
                                } catch (Exception exception1) {
                                    System.out.println("Failed to reconnect, trying again...");
                                    if (STACKTRACE) exception1.printStackTrace(System.err);
                                }
                            }
                        }, Duration.ofSeconds(30).toMillis());
                    } catch (Exception exception1) {
                        System.out.println("Connection failed, exiting...");
                        exception1.printStackTrace(System.err);
                        System.exit(1);
                    }
                }
                if (STACKTRACE) {
                    exception.printStackTrace(System.err);
                }
            }
        });
        CompletableFuture.runAsync(
                () -> REALTIME_CLIENT.connect(() -> System.out.println("[Client] Client is ready!"))
        ).exceptionally(throwable -> {
            System.out.println("[Client] Failed to connect: " + throwable.getMessage());
            if (STACKTRACE) throwable.printStackTrace(System.err);
            return null;
        });
    }

    @SuppressWarnings("ALL")
    private static void loadConversation() {
        try {
            final File file = new File("conversations.txt");
            if (!file.exists()) file.createNewFile();
            Files.readAllLines(file.toPath()).forEach(string -> {
                if (!string.isEmpty()) {
                    final String[] values = string.split("\u2503");
                    final ConversationEntry chatMessage = new ConversationEntry(values[0], values[1]);
                    GPT_CONVERSATIONS.add(chatMessage);
                }
            });
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
        }
    }

    @SuppressWarnings("unused")
    private static void performOrderCheckAndCleanup() {
        /*
        0 = user
        1 = bot
        2 = user
        ...
        n = something
        so %2 is user, otherwise is bot
         */
        int toRemoveFrom = -1;
        for (int i = 0; i < GPT_CONVERSATIONS.size(); i++) {
            final ConversationEntry entry = GPT_CONVERSATIONS.get(i);
            if ((i % 2 == 0 && entry.role().equals("user")) || (i % 2 !=  0 && entry.role().equals("assistant"))) continue;
            toRemoveFrom = i;
            break;
        }

        if (toRemoveFrom != -1) {
            final int size = GPT_CONVERSATIONS.size();
            GPT_CONVERSATIONS.removeAll(GPT_CONVERSATIONS.subList(toRemoveFrom, size));
        }
        final ConversationEntry lastEntry = GPT_CONVERSATIONS.get(GPT_CONVERSATIONS.size() - 1);
        if (lastEntry.role().equals("user")) {
            GPT_CONVERSATIONS.remove(lastEntry);
        }
    }

    private static void resetConversation() {
        GPT_CONVERSATIONS.clear();
    }

    private static void saveMessage(final ConversationEntry chatMessage) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter("conversations.txt", true))) {
            writer.append(chatMessage.role()).append("┃").append(chatMessage.content().replaceAll("\n", "<newline>"));
            writer.newLine();
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
        }
    }

    public static List<ConversationEntry> generateSingletonConversation() {
        final List<ConversationEntry> list = new ArrayList<>();
        list.add(GPT_CONVERSATIONS.get(0));
        list.add(GPT_CONVERSATIONS.get(1));
        final StringBuilder builder = new StringBuilder();
        for (int i = 2; i < GPT_CONVERSATIONS.size(); i++) {
            final ConversationEntry entry = GPT_CONVERSATIONS.get(i);
            if (entry.role().equals("assistant") && entry.content().equals("Message has been saved")) continue;
            builder.append("\n").append(entry.role().equals("assistant") ? "[MODEL ANSWER] " : "").append(entry.content());
        }
        final ConversationEntry entry = new ConversationEntry("user", builder.toString().replaceFirst("\n", ""));
        list.add(entry);
        return list;
    }

    private static void prepareOpenAiClient() {
        GptApi.OPENAI_CLIENT = new OpenAIClientBuilder()
                .credential(new KeyCredential(Credentials.OPENAI_TOKEN))
                .buildClient();
        System.out.println("[OpenAI] Client is ready!");
    }

}
