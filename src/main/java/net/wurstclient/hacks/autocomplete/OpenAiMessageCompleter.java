/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.autocomplete;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonObject;

public final class OpenAiMessageCompleter extends MessageCompleter
{
	public OpenAiMessageCompleter(ModelSettings modelSettings)
	{
		super(modelSettings);
	}
	
	@Override
	protected JsonObject buildParams(String prompt, int maxSuggestions)
	{
		// build the request parameters
		JsonObject params = new JsonObject();
		params.addProperty("stop",
			modelSettings.stopSequence.getSelected().getSequence());
		params.addProperty("max_tokens", modelSettings.maxTokens.getValueI());
		params.addProperty("temperature", modelSettings.temperature.getValue());
		params.addProperty("top_p", modelSettings.topP.getValue());
		params.addProperty("presence_penalty",
			modelSettings.presencePenalty.getValue());
		params.addProperty("frequency_penalty",
			modelSettings.frequencyPenalty.getValue());
		params.addProperty("n", maxSuggestions);
		
		// determine model name and type
		boolean customModel = !modelSettings.customModel.getValue().isBlank();
		String modelName = customModel ? modelSettings.customModel.getValue()
			: "" + modelSettings.openAiModel.getSelected();
		boolean chatModel =
			customModel ? modelSettings.customModelType.getSelected().isChat()
				: modelSettings.openAiModel.getSelected().isChatModel();
		
		// add the model name
		params.addProperty("model", modelName);
		
		// add the prompt, depending on model type
		if(chatModel)
		{
			JsonArray messages = new JsonArray();
			JsonObject systemMessage = new JsonObject();
			systemMessage.addProperty("role", "system");
			systemMessage.addProperty("content",
				"Complete the following text. Reply only with the completion."
					+ " You are not an assistant.");
			messages.add(systemMessage);
			JsonObject promptMessage = new JsonObject();
			promptMessage.addProperty("role", "user");
			promptMessage.addProperty("content", prompt);
			messages.add(promptMessage);
			params.add("messages", messages);
			
		}else
			params.addProperty("prompt", prompt);
		
		return params;
	}
	
	@Override
	protected WsonObject requestCompletions(JsonObject parameters)
		throws IOException, JsonException
	{
		// get and validate the API URL
		URL url = getValidatedEndpoint();
		
		// set up the API request
		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setRequestProperty("Authorization",
			"Bearer " + System.getenv("WURST_OPENAI_KEY"));
		
		// set the request body
		conn.setDoOutput(true);
		try(OutputStream os = conn.getOutputStream())
		{
			os.write(JsonUtils.GSON.toJson(parameters).getBytes());
			os.flush();
		}
		
		// parse the response
		return JsonUtils.parseConnectionToObject(conn);
	}

	private URL getValidatedEndpoint() throws IOException
	{
		boolean customModel = !modelSettings.customModel.getValue().isBlank();
		boolean chatModel =
			customModel ? modelSettings.customModelType.getSelected().isChat()
				: modelSettings.openAiModel.getSelected().isChatModel();
		String endpoint = chatModel ? modelSettings.openaiChatEndpoint.getValue()
			: modelSettings.openaiLegacyEndpoint.getValue();

		try
		{
			URI uri = URI.create(endpoint).normalize();

			if(uri.getScheme() == null || !uri.getScheme().equals("https"))
				throw new IOException(
					"Refusing to send API requests to a non-HTTPS endpoint: "
						+ endpoint);

			if(uri.getUserInfo() != null)
				throw new IOException(
					"Endpoint URLs must not include user credentials.");

			if(uri.getHost() == null || uri.getHost().isBlank())
				throw new IOException("Endpoint URL is missing a host.");

			return uri.toURL();

		}catch(IllegalArgumentException e)
		{
			throw new IOException("Invalid API endpoint URL: " + endpoint, e);
		}
	}
	
	@Override
	protected String[] extractCompletions(WsonObject response)
		throws JsonException
	{
		ArrayList<String> completions = new ArrayList<>();
		
		// extract choices from response
		ArrayList<WsonObject> choices =
			response.getArray("choices").getAllObjects();
		
		// extract completions from choices
		if(modelSettings.openAiModel.getSelected().isChatModel())
			for(WsonObject choice : choices)
			{
				WsonObject message = choice.getObject("message");
				String content = message.getString("content");
				completions.add(content);
			}
		else
			for(WsonObject choice : choices)
				completions.add(choice.getString("text"));
			
		// remove newlines
		for(String completion : completions)
			completion = completion.replace("\n", " ");
		
		return completions.toArray(new String[completions.size()]);
	}
}
