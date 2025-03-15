package dev.akraml.brainey;

import com.google.gson.JsonObject;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class BraineyV2 {

    private static final ScheduledExecutorService FILE_REWRITE_POOL = Executors.newScheduledThreadPool(1);
    private static final Scanner SCANNER = new Scanner(System.in);
    private static final List<ConversationEntry> GPT_CONVERSATIONS = new ArrayList<>();
    private static final Set<Long> GPT_GENERATING = new HashSet<>();
    private static boolean STACKTRACE = true;

//    public static void main(String[] args) {
//        try {
//            final Instagram instagram = Instagram.login("brainey.v2","Brainey@2024");
//            System.out.println("connected");
//            instagram.direct().attachNotificationListener(message -> {
//                if (message.text.startsWith("@brainey.v2 ")) {
//                    final String msg = message.text.replaceFirst("@brainey.v2 ", "");
//                    try {
//
//                        final JsonObject response = GptApi.getResponse(Collections.singletonList(new ConversationEntry("user", msg)));
//                        instagram.direct().groupMessage(response.toString(), "itsakraml");
//                    } catch (Exception exception) {
//                        exception.printStackTrace(System.err);
//                        message.reply("Sorry, but an error occurred when trying to generate a response.");
//                    }
//                }
//            });
//            instagram.relogin();
//        } catch (Exception exception) {
//            exception.printStackTrace(System.out);
//        }
//    }

}
