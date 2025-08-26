package com.costacruise.core.services;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component(service = AmadeusAccessToken.class, immediate = true)
public class AmadeusAccessToken {
  Logger log = LoggerFactory.getLogger(AmadeusAccessToken.class);

  HttpClient client = HttpClient.newHttpClient(); // create once and reuse if possible
  
  public String getAccessToken() {
    String url = "https://test.api.amadeus.com/v1/security/oauth2/token";
    String client_id = "yW2fc55cNybqDZ3iThpnwVA6IiZsh6uJ";
    String client_secret = "KCwT151Dc2AM7EiK";
    String grant_type = "client_credentials";

    try {
        String bodyForm = "grant_type=" + URLEncoder.encode(grant_type, StandardCharsets.UTF_8) +
                "&client_id=" + URLEncoder.encode(client_id, StandardCharsets.UTF_8) +
                "&client_secret=" + URLEncoder.encode(client_secret, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(bodyForm))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            log.error("Failed to get access token: {} - {}", response.statusCode(), response.body());
            return null;
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(response.body());
        String accessToken = rootNode.get("access_token").asText();
        log.info("Access token: {}", accessToken);
        return accessToken;
    } catch (Exception e) {
        log.error("Exception while getting access token", e);
        return null;
    }
  }
}
