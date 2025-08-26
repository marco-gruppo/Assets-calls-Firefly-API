package com.costacruise.core.services;

import org.osgi.service.component.annotations.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component(service = FindLowestPrice.class, immediate = true)
public class FindLowestPrice {
  
  public static String findLowestPrice(String jsonResponse) {
    try {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonResponse);

        JsonNode dataArray = root.path("data");
        double lowestPrice = Double.MAX_VALUE;

        for (JsonNode item : dataArray) {
            String priceStr = item.path("price").path("total").asText();
            double price = Double.parseDouble(priceStr);
            if (price < lowestPrice) {
                lowestPrice = price;
            }
        }

        return String.valueOf(lowestPrice);
    } catch (Exception e) {
        e.printStackTrace();
        return "Error processing JSON.";
    }
  }
}
