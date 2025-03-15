package dev.akraml.brainey;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;

import java.io.StringReader;
import java.net.http.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Groq API implementation.
 *
 * @author AkramL
 */
public class GroqApi {
	
	private final String apiKey;
	private final HttpClient client;
	private final Gson gson = new Gson();
	
	public GroqApi(String apiKey) {
		this.apiKey = apiKey;
		this.client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_2)
			.build();
	}
	
	public JsonObject createChatCompletion(JsonObject request) {
		HttpRequest httpRequest = HttpRequest.newBuilder()
			.uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + apiKey)
			.POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8))
			.build();
		
		try {
			String response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString()).body();
			return gson.fromJson(response, JsonObject.class);
		} catch (Exception e) {
			return null;
		}
	}
	
	public Single<JsonObject> createChatCompletionAsync(JsonObject request) {
		HttpRequest httpRequest = HttpRequest.newBuilder()
			.uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + apiKey)
			.POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8))
			.build();
		
		return Single.<HttpResponse<String>>create(emitter -> {
			client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
				.thenAccept(emitter::onSuccess)
				.exceptionally(throwable -> {
					emitter.onError(throwable);
					return null;
				});
		}).map(HttpResponse::body)
		.map(body -> gson.fromJson(body, JsonObject.class));
	}
	
	public Observable<JsonObject> createChatCompletionStreamAsync(JsonObject request) {
		HttpRequest httpRequest = HttpRequest.newBuilder()
			.uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + apiKey)
			.POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8))
			.build();
			
		return Observable.<String>create(emitter -> {
			client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
				try {
					String[] lines = response.body().split("\n");
					for (String line : lines) {
						if (emitter.isDisposed()) break;
						emitter.onNext(line);
					}
					emitter.onComplete();
				} catch (Exception exception) {
					emitter.onError(exception);
				}
			}).exceptionally(throwable -> {
				emitter.onError(throwable);
				return null;
			});
		}).filter(line -> line.startsWith("data: "))
			.map(line -> line.substring(6))
			.filter(jsonData -> !jsonData.equals("[DONE]"))
			.map(jsonData -> gson.fromJson(jsonData, JsonObject.class));
	}
	
}