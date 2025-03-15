package dev.akraml.brainey;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.util.ArrayList;
import java.util.List;

public class GptApi {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final MediaType MEDIA_TYPE = MediaType.parse("application/json");
    public static OpenAIClient OPENAI_CLIENT;
	private static GroqApi groq = new GroqApi(Credentials.GROQ_API_KEY);

    public static String getResponseGpt(List<ConversationEntry> messages) {
        List<ChatRequestMessage> messageList = new ArrayList<>();
        // Convert to gpt entries
        for (ConversationEntry entry : messages) {
            String role = entry.role();
            if (role.equalsIgnoreCase("system")) messageList.add(new ChatRequestSystemMessage(entry.content()));
            else if (role.equalsIgnoreCase("user")) messageList.add(new ChatRequestUserMessage(entry.content()));
            else if (role.equalsIgnoreCase("assistant")) messageList.add(new ChatRequestAssistantMessage(entry.content()));
        }

        ChatCompletions completions = OPENAI_CLIENT.getChatCompletions("gpt-4o", new ChatCompletionsOptions(messageList).setTemperature(0.86D));
        return completions.getChoices().get(0).getMessage().getContent();
    }
	
	public static String getResponseGroq(final List<ConversationEntry> messages, GroqModels model) {
		JsonObject requestObject = new JsonObject();
		JsonArray messagesArray = new JsonArray();
		
		// Add system prompt
		JsonObject systemObject = new JsonObject();
		systemObject.addProperty("role", "system");
		systemObject.addProperty("content", Credentials.SYS_PROMPT);
		messagesArray.add(systemObject);
		
		// Add messages
		for (ConversationEntry entry : messages) {
			JsonObject messageObject = new JsonObject();
			messageObject.addProperty("role", entry.role());
			messageObject.addProperty("content", entry.content());
			messagesArray.add(messageObject);
		}
		requestObject.add("messages", messagesArray);
		requestObject.addProperty("model", model.getName());
		requestObject.addProperty("temperature", 1);
		requestObject.addProperty("max_tokens", 1024);
		requestObject.addProperty("top_p", 1);
		requestObject.addProperty("stream", false);
		requestObject.add("stop", null);
		
		JsonObject responseObject = groq.createChatCompletion(requestObject);
		
		JsonArray choicesArray = responseObject.get("choices").getAsJsonArray();
		String message = choicesArray.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
		return message;
	}

    public static JsonObject getResponse(final List<ConversationEntry> messages) throws Exception {
        final JsonObject requestObject = new JsonObject();
        requestObject.addProperty("chatId", "92d97036-3e25-442b-9a25-096ab45b0525");
        final JsonArray messagesArray = new JsonArray();
        for (final ConversationEntry entry : messages) {
            final JsonObject jsonEntry = new JsonObject();
            jsonEntry.addProperty("role", entry.role());
            jsonEntry.addProperty("content", entry.content());
            messagesArray.add(jsonEntry);
        }
        requestObject.add("messages", messagesArray);
        // Requesting now
        //noinspection deprecation
        final RequestBody body = RequestBody.create(MEDIA_TYPE, requestObject.toString());
        final Request request = new Request.Builder()
                .url("https://chat-gtp-free.p.rapidapi.com/v1/chat/completions")
                .post(body)
                .addHeader("content-type", "application/json")
                .addHeader("X-RapidAPI-Key", "36f08a7b1fmsh7ea830184f2e481p1564e6jsn424d2605e95c")
                .addHeader("X-RapidAPI-Host", "chat-gtp-free.p.rapidapi.com")
                .build();

        // Get a response
        try (final Response response = CLIENT.newCall(request).execute()) {
            final ResponseBody responseBody = response.body();
            if (response.code() != 200 || responseBody == null) return null;
            return GSON.fromJson(responseBody.string(), JsonObject.class);
        }
    }

    public static JsonObject generateGemini(final List<ConversationEntry> messages) throws Exception {
        final JsonObject jsonObject = new JsonObject();

        // contents
        final JsonArray contentsArray = new JsonArray();
        for (final ConversationEntry entry : Main.generateSingletonConversation()) {
            final JsonObject contentObject = new JsonObject();
            contentObject.addProperty("role", entry.role());
            final JsonArray partsArray = new JsonArray();
            final JsonObject partObject = new JsonObject();
            partObject.addProperty("text", entry.content());
            partsArray.add(partObject);
            contentObject.add("parts", partsArray);
            contentsArray.add(contentObject);
        }
        jsonObject.add("contents", contentsArray);

        // generationConfig
        final JsonObject generationConfigObject = new JsonObject();
        generationConfigObject.addProperty("temperature", 0.9f);
        generationConfigObject.addProperty("topK", 1);
        generationConfigObject.addProperty("topP", 1);
        generationConfigObject.addProperty("maxOutputTokens", 2048);
        generationConfigObject.add("stopSequences", new JsonArray());
        jsonObject.add("generationConfig", generationConfigObject);

        // safetySettings
        final JsonArray safetySettingsArray = new JsonArray();
        for (final SafetySettings settings : SafetySettings.values()) {
            final JsonObject safetyObject = new JsonObject();
            safetyObject.addProperty("category", settings.name());
            safetyObject.addProperty("threshold", "BLOCK_NONE");
            safetySettingsArray.add(safetyObject);
        }
        jsonObject.add("safetySettings", safetySettingsArray);

        //noinspection deprecation
        final RequestBody requestBody = RequestBody.create(MEDIA_TYPE, jsonObject.toString());
        final Request request = new Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=" + Credentials.GEMINI_API_KEY)
                .post(requestBody)
                .build();
        try (final Response response = CLIENT.newCall(request).execute()) {
            final ResponseBody responseBody = response.body();
            if (responseBody == null) return null;
            final String bodyString = responseBody.string();
            if (response.code() != 200) {
                System.out.println("[IN]");
                System.out.println(GSON.toJson(jsonObject));
                System.out.println("[OUT]");
                System.out.println(GSON.toJson(GSON.fromJson(bodyString, JsonObject.class)));
            }
            return GSON.fromJson(bodyString, JsonObject.class);
        }
    }

    public enum SafetySettings {
        HARM_CATEGORY_HARASSMENT, HARM_CATEGORY_HATE_SPEECH, HARM_CATEGORY_SEXUALLY_EXPLICIT
    }

}
