package com.costacruise.core.servlets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.propertytypes.ServiceDescription;

import com.costacruise.core.services.AmadeusAccessToken;
import com.costacruise.core.services.FindLowestPrice;
import com.google.gson.JsonObject;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;

@Component(service = { Servlet.class })
@SlingServletResourceTypes(
        resourceTypes="srilanka-airlines/flight-price-offer",
        methods=HttpConstants.METHOD_GET)
@ServiceDescription("Get Flight Price Offer Servlet")
public class GetFlightPriceOffer extends SlingAllMethodsServlet{
  
  @Reference
  private transient AmadeusAccessToken amadeusAccessToken;

  @Reference
  private transient FindLowestPrice findLowestPrice;

  private static final HttpClient CLIENT = HttpClient.newHttpClient(); // reuse if possible

  @Override
  protected void doGet(final SlingHttpServletRequest request, final SlingHttpServletResponse response) throws ServletException, IOException {
      final String AUTH_TOKEN = "Bearer " + amadeusAccessToken.getAccessToken();

      String origin = request.getParameter("origin");
      String destination = request.getParameter("destination");
      String reqUrl = "https://test.api.amadeus.com/v2/shopping/flight-offers?originLocationCode=" + origin + "&destinationLocationCode=" + destination + "&departureDate=2025-07-15&adults=1";

      try {
          HttpRequest req = HttpRequest.newBuilder()
              .uri(URI.create(reqUrl))
              .header("Content-Type", "application/json")
              .header("Authorization", AUTH_TOKEN)
              .GET()
              .build();

          HttpResponse<String> apiResponse = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
          String lowestPrice = FindLowestPrice.findLowestPrice(apiResponse.body());

          response.setContentType("application/json");
          response.setStatus(apiResponse.statusCode());
          response.setHeader("Access-Control-Allow-Origin", "*");

          JsonObject jsonObject = new JsonObject();
          jsonObject.addProperty("lowestPrice", lowestPrice);
          response.getWriter().write(jsonObject.toString());

      } catch (Exception e) {
          response.setStatus(500);
          response.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
          response.setHeader("Access-Control-Allow-Origin", "*");
      }
  }
}
