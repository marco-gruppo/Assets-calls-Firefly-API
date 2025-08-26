package com.adoberaffaeledematteisprogram.core.workflow;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.commons.util.DamUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.Session;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

@Component(
        service = WorkflowProcess.class,
        property = {"process.label=Costa Cruises : Firefly Generation"}
)
public class FireflyGenerationWFProcess implements WorkflowProcess {

    private static final Logger LOG = LoggerFactory.getLogger(FireflyGenerationWFProcess.class);

    private static final String TOKEN_URL = "https://ims-na1.adobelogin.com/ims/token/v2";
    private static final String UPLOAD_URL = "https://firefly-api.adobe.io/v2/storage/image";
    private static final String EXPAND_URL = "https://firefly-api.adobe.io/v3/images/expand";
    private static final String ASYNC_EXPAND_URL = "https://firefly-api.adobe.io/v3/images/expand-async";
    private static final String ASYNC_STATUS_URL = "https://firefly-epo853211.adobe.io/v3/status/";
    private static final String CLIENT_ID = "13ccf78ae0904d2e832931ff2b61cd6a";
    private static final String CLIENT_SECRET = "p8e-CBSC2v6ohUBgX3DoVu0ehF2wtja9TxXm";
    private static final String API_KEY = "13ccf78ae0904d2e832931ff2b61cd6a";
    private static final String SERVICE_USER = "read-write-service";
    private String accessToken = "";
    private static final ObjectMapper mapper = new ObjectMapper();
    ResourceResolver adminResolver;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) {
        String payloadPath = item.getWorkflowData().getPayload().toString();

        if (!payloadPath.startsWith("/content/dam/")) {
            LOG.error("Payload is not a DAM asset: " + payloadPath);
            return;
        }
        if (payloadPath.startsWith("/content/dam/costa-crociere/fire-fly-generation")) {

            LOG.info("payload path : " + payloadPath);
            Session adminSession;

            try {

                adminResolver = getServiceResolver();
                if (adminResolver == null) {
                    LOG.error("Failed to get service resolver");
                    return;
                }
                
                adminSession = adminResolver.adaptTo(Session.class);
                if (adminSession == null) {
                    LOG.error("Failed to get admin session");
                    return;
                }

                // Step 1: Get Access Token
                accessToken = fetchAccessToken();
                if (StringUtils.isBlank(accessToken)) {
                    LOG.error("Failed to fetch access token");
                    return;
                }
                
                LOG.info("Binary File res path :" + payloadPath + "/jcr:content");
                Resource assetBinRes = adminResolver.getResource(payloadPath + "/jcr:content");
                LOG.info("Binary File res :" + assetBinRes);

                if (assetBinRes == null) {
                    LOG.error("Binary not found at: " + payloadPath);
                    return;
                }

                Node node = assetBinRes.adaptTo(Node.class);
                if (node == null) {
                    LOG.error("Failed to adapt resource to node");
                    return;
                }

                try (InputStream inputStream = node.getProperty("jcr:data").getBinary().getStream()) {
                    byte[] imageBytes = IOUtils.toByteArray(inputStream);

                    // Step 2: Upload Image to get uploadId
                    String uploadId = uploadImage(imageBytes);
                    if (StringUtils.isBlank(uploadId)) {
                        LOG.error("Failed to upload image");
                        return;
                    }
                    
                    Resource folderRes = getFolderOfImageFromRendition(payloadPath);
                    if (folderRes == null) {
                        LOG.error("Failed to get folder resource");
                        return;
                    }

                    String fireflyDimensions = readPropertyFromFolder(folderRes, "fireflyDimensions");

                    String width = "1024"; // default values
                    String height = "1024";

                    if (StringUtils.isNotBlank(fireflyDimensions) && fireflyDimensions.contains("x")) {
                        try {
                            String[] dimensions = fireflyDimensions.split("x");
                            if (dimensions.length == 2) {
                                height = dimensions[0].trim(); // height is before x
                                width = dimensions[1].trim();  // width is after x
                            }
                        } catch (NumberFormatException e) {
                            LOG.error("Error parsing fireflyDimensions: " + fireflyDimensions, e);
                        }
                    }

                    if (StringUtils.isNotBlank(height) && StringUtils.isNotBlank(width)) {

                        //Trigger Firefly expand UI on upload Id
                        String statusMsg = triggerDeprecatedExpand(uploadId, payloadPath, Integer.parseInt(width), Integer.parseInt(height), accessToken);
                        LOG.info("statusMsg :" + statusMsg);
                        //Trigger Firefly async expand UI on upload Id
                       
                        /*String statusURL = triggerAsyncExpand(uploadId, payloadPath, Integer.parseInt(width), Integer.parseInt(height), accessToken);
                        if (statusURL == null) {
                            LOG.error("FAILED TO START ASYNC EXPAND JOB");
                        } else {
                            // Poll until job is complete
                            String statusResponse = waitForCompletion(statusURL, payloadPath);
                            LOG.info("STATUSRESPONSE :" + statusResponse);
                        }
                        */
                    }
                }
            } catch (Exception e) {
                LOG.error("Workflow execution failed", e);
            } finally {
                // Clean up resources
                if (adminResolver != null && adminResolver.isLive()) {
                    adminResolver.close();
                }
            }
        }
    }

    private ResourceResolver getServiceResolver() {
        try {
            return resolverFactory.getAdministrativeResourceResolver(null);
        } catch (Exception e) {
            LOG.error("Failed to get service resolver", e);
            return null;
        }
    }

    private String fetchAccessToken() {
        try (CloseableHttpClient client = HttpClients.createDefault()) {

            HttpPost post = new HttpPost(TOKEN_URL);
            post.setHeader("Content-Type", "application/x-www-form-urlencoded");
            String body = String.format("grant_type=client_credentials&client_id=%s&client_secret=%s&scope=openid,AdobeID,read_organizations,session,ff_apis,firefly_api",
                    CLIENT_ID, CLIENT_SECRET);
            post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = client.execute(post)) {
                String json = EntityUtils.toString(response.getEntity());

                JsonNode root = mapper.readTree(json);
                return root.get("access_token").asText();
            }
        } catch (Exception e) {
            LOG.error("Failed to fetch access token", e);
            return null;
        }
    }

    private String uploadImage(byte[] imageBytes) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(UPLOAD_URL);
            post.setHeader("Authorization", "Bearer " + accessToken);
            post.setHeader("x-api-key", API_KEY);
            post.setHeader("Content-Type", "image/png");

            post.setEntity(new ByteArrayEntity(imageBytes, ContentType.create("image/png")));

            HttpResponse response = client.execute(post);
            String json = EntityUtils.toString(response.getEntity());
            JsonNode root = mapper.readTree(json);

            LOG.info("Response of upload call :" + json);

            return root.get("images").get(0).get("id").asText();
        } catch (Exception e) {
            LOG.error("Failed to upload image", e);
            return null;
        }
    }

    private String triggerDeprecatedExpand(String uploadId, String assetPath, int width, int height, String accessToken) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {

            LOG.info("Access Token :" + accessToken);

            ObjectNode payload = mapper.createObjectNode();
            payload.put("numVariations", 1);

            ObjectNode sizeNode = mapper.createObjectNode();
            sizeNode.put("width", width);
            sizeNode.put("height", height);
            payload.set("size", sizeNode);

            ObjectNode sourceNode = mapper.createObjectNode();
            sourceNode.put("uploadId", uploadId);
            ObjectNode imageNode = mapper.createObjectNode();
            imageNode.set("source", sourceNode);
            payload.set("image", imageNode);

            ObjectNode alignmentNode = mapper.createObjectNode();
            alignmentNode.put("horizontal", "center");
            alignmentNode.put("vertical", "center");
            ObjectNode placementNode = mapper.createObjectNode();
            placementNode.set("alignment", alignmentNode);
            payload.set("placement", placementNode);

            // Convert to string
            String jsonString = mapper.writeValueAsString(payload);

            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {

                HttpPost postRequest = new HttpPost("https://firefly-api.adobe.io/v3/images/expand");

                // Set headers exactly as in the working Postman example
                postRequest.setHeader("Content-Type", "application/json");
                postRequest.setHeader("x-api-key", API_KEY);
                postRequest.setHeader("Authorization", "Bearer " + accessToken);
                // Add Accept header to specify expected response format
                postRequest.setHeader("Accept", "application/json");

                LOG.info("Payload JSON: " + jsonString);

                StringEntity entity = new StringEntity(jsonString, ContentType.APPLICATION_JSON);
                postRequest.setEntity(entity);

                // Execute request
                try (CloseableHttpResponse response = httpClient.execute(postRequest)) {
                    if (response.getStatusLine().getStatusCode() == 200) {
                        String json = EntityUtils.toString(response.getEntity());
                        JsonNode result = mapper.readTree(json);

                        // Extract the image URL from the response
                        JsonNode outputs = result.get("outputs").get(0);
                        String imageUrl = outputs.get("image").get("url").asText();

                        // Download and save the expanded image
                        byte[] imageBytes = downloadImage(imageUrl);
                        saveAsRendition(assetPath, imageBytes, "image/jpeg");
                        return "success";

                    } else {
                        LOG.error("Failed to trigger expand :" + response.getStatusLine().getStatusCode());
                        return "failure";
                    }

                }
            }
        } catch (Exception e) {
            LOG.error("Failed to trigger deprecated expand", e);
            return "failure";
        }
    }

    private String triggerAsyncExpand(String uploadId, String assetPath, int width, int height, String accessToken) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {

            LOG.info("Access Token :" + accessToken);

            ObjectNode payload = mapper.createObjectNode();
            payload.put("numVariations", 1);

            ObjectNode sizeNode = mapper.createObjectNode();
            sizeNode.put("width", width);
            sizeNode.put("height", height);
            payload.set("size", sizeNode);

            // Image source configuration
            ObjectNode imageSourceNode = mapper.createObjectNode();
            imageSourceNode.put("uploadId", uploadId);
            ObjectNode imageNode = mapper.createObjectNode();
            imageNode.set("source", imageSourceNode);
            payload.set("image", imageNode);

            // Mask source configuration
            ObjectNode maskSourceNode = mapper.createObjectNode();
            maskSourceNode.put("uploadId", uploadId);
            ObjectNode maskNode = mapper.createObjectNode();
            maskNode.put("invert", false);
            maskNode.set("source", maskSourceNode);
            payload.set("mask", maskNode);

            // Convert to string
            String jsonString = mapper.writeValueAsString(payload);

            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {

                HttpPost postRequest = new HttpPost(ASYNC_EXPAND_URL);
                LOG.info("POST CALL TO " + ASYNC_EXPAND_URL);

                // Set headers exactly as in the working Postman example
                postRequest.setHeader("Content-Type", "application/json");
                postRequest.setHeader("x-api-key", API_KEY);
                postRequest.setHeader("Authorization", "Bearer " + accessToken);
                // Add Accept header to specify expected response format
                postRequest.setHeader("Accept", "application/json");

                LOG.info("Payload JSON from Async: " + jsonString);

                StringEntity entity = new StringEntity(jsonString, ContentType.APPLICATION_JSON);
                postRequest.setEntity(entity);

                // Execute request
                try (CloseableHttpResponse response = httpClient.execute(postRequest)) {
                    LOG.info("Status of Async Expand Call " + response.getStatusLine().getStatusCode());
                    if (response.getStatusLine().getStatusCode() == 202) {

                        String body = EntityUtils.toString(response.getEntity());
                        LOG.info("Async Call body : " + body);

                        return extractstatusUrl(body);

                    } else {
                        LOG.error("Failed to trigger expand :" + response.getStatusLine().getStatusCode());
                        return null;
                    }

                }
            }
        } catch (Exception e) {
            LOG.error("Failed to trigger async expand", e);
            return null;
        }
    }

    private String waitForCompletion(String statusURL, String assetPath) {
        String status = "";
        int maxAttempts = 3;
        int attempt = 0;
        while (attempt < maxAttempts) {
            LOG.info("Attempt number :" + attempt);

            try {
                Thread.sleep(20_000);
                attempt++;

                String statusResponse = getJobStatus(statusURL);
                LOG.info("Job Status response : " + statusResponse + " From URL : " + statusURL);
                if (statusResponse == null) {
                    LOG.error("Failed to get job status for statusURL: " + statusURL);
                    continue;
                }
                
                status = extractStatus(statusResponse);

                LOG.info("Current status for job {}: {} (attempt {}/{})", status, attempt, maxAttempts);

                if ("succeeded".equalsIgnoreCase(status)) {
                    JsonNode result = mapper.readTree(statusResponse);

                    // Extract the image URL from the response with proper null checks
                    // The outputs array is nested inside a 'result' object
                    JsonNode resultObj = result.get("result");
                    if (resultObj == null) {
                        LOG.error("Result object not found in response for jobId");
                        return statusResponse;
                    }
                    
                    JsonNode outputs = resultObj.get("outputs");
                    if (outputs == null || !outputs.isArray() || outputs.size() == 0) {
                        LOG.error("No outputs found in result");
                        return statusResponse;
                    }
                    
                    JsonNode firstOutput = outputs.get(0);
                    if (firstOutput == null) {
                        LOG.error("First output is null");
                        return statusResponse;
                    }
                    
                    JsonNode image = firstOutput.get("image");
                    if (image == null) {
                        LOG.error("Image object not found in output for jobId");
                        return statusResponse;
                    }
                    
                    JsonNode url = image.get("url");
                    if (url == null) {
                        LOG.error("Image URL not found for jobId");
                        return statusResponse;
                    }
                    
                    String finalImageURL = url.asText();
                    if (StringUtils.isBlank(finalImageURL)) {
                        LOG.error("Image URL is empty for jobId");
                        return statusResponse;
                    }

                    // Download and save the expanded image
                    byte[] imageBytes = downloadImage(finalImageURL);
                    if (imageBytes != null) {
                        saveAsRendition(assetPath, imageBytes, "image/jpeg");
                    }

                    LOG.info("Status Job completed successfully");
                    return statusResponse;
                } else if ("failed".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)) {
                    LOG.error("Job failed for jobId with status: " + status);
                    return statusResponse;
                }
                // If status is "pending" or "processing", continue polling

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.error("Polling interrupted for jobId: " + e.getMessage(), e);
                return "";
            } catch (Exception e) {
                LOG.error("Error polling status for job (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                // Continue polling unless we've exhausted all attempts
                if (attempt > maxAttempts - 1) {
                    LOG.error("Failed to get job status after " + maxAttempts + " attempts", e);
                    return "";
                }
            }
        }

        LOG.error("Job timeout after " + maxAttempts + " minutes for jobId");
        return "";
    }

    private String extractStatus(String json) {
        try {
            JsonNode result = mapper.readTree(json);
            return result.get("status").asText();
        } catch (IOException e) {
            LOG.error("Error parsing status from Status call: " + json, e);
            return "";
        }
    }
    
    private String extractstatusUrl(String json) {
        String statusUrl = "";
        try {
            JsonNode result = mapper.readTree(json);

            // Extract the status URL from the response with null check
            JsonNode statusUrlNode = result.get("statusUrl");
            if (statusUrlNode != null) {
                statusUrl = statusUrlNode.asText();
                LOG.info("statusUrl : " + statusUrl);
            } else {
                LOG.error("statusUrl not found in response: " + json);
            }

        } catch (IOException e) {
            LOG.error("IOException in Response Parsing of Expand API for getting statusUrl", e);
        }
        return statusUrl;
    }
    

    private String extractJobId(String json) {
        String jobId = "";
        try {
            JsonNode result = mapper.readTree(json);

            // Extract the image URL from the response
            jobId = result.get("jobId").asText();
            LOG.info("jobId : " + jobId);

        } catch (IOException e) {
            LOG.error("IOException in Response Parsing of Expand API", e);
        }
        return jobId;
    }

    private String getJobStatus(String statusUrl) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(statusUrl);
            get.setHeader("Accept", "application/json");
            get.setHeader("Content-Type", "application/json");
            get.setHeader("Authorization", "Bearer " + accessToken);

            try (CloseableHttpResponse response = client.execute(get)) {
            	String responseBody = EntityUtils.toString(response.getEntity());
            	LOG.info("Body of status call: " + responseBody);
                return responseBody;
            }
        } catch (Exception e) {
            LOG.error("Failed to get job status using URL: " + statusUrl, e);
            return null;
        }
    }

    private byte[] downloadImage(String url) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(url);
            HttpResponse response = client.execute(get);
            return IOUtils.toByteArray(response.getEntity().getContent());
        } catch (Exception e) {
            LOG.error("Failed to download image from URL: " + url, e);
            return null;
        }
    }

    private void saveAsRendition(String assetPath, byte[] imageBytes, String mimeType) {
        try {
            Resource assetRes = adminResolver.getResource(assetPath);
            if (assetRes == null) {
                LOG.error("Asset not found: " + assetPath);
                return;
            }

            Node assetNode = assetRes.adaptTo(Node.class);
            if (assetNode == null) {
                LOG.error("Failed to adapt asset resource to node");
                return;
            }
            
            String assetNodePath = assetNode.getPath();
            LOG.info("assetNode :" + assetNodePath);
            
            Node renditionsNode;
            
            // Check if the path contains 'renditions'
            if (assetNodePath.contains("renditions")) {
                // Extract the path up to and including 'renditions'
                String[] pathSegments = assetNodePath.split("/");
                StringBuilder renditionsPath = new StringBuilder();
                
                for (String segment : pathSegments) {
                    renditionsPath.append("/").append(segment);
                    if ("renditions".equals(segment)) {
                        break;
                    }
                }
                
                String renditionsPathStr = renditionsPath.toString();
                LOG.info("Extracted renditions path: " + renditionsPathStr);
                
                // Get the renditions node from the extracted path
                Resource renditionsResource = adminResolver.getResource(renditionsPathStr);
                if (renditionsResource != null) {
                    renditionsNode = renditionsResource.adaptTo(Node.class);
                } else {
                    LOG.error("Renditions node not found at path: " + renditionsPathStr);
                    return;
                }
            } else {
                // Original logic: create renditions node if it doesn't exist
                renditionsNode = assetNode.hasNode("renditions")
                        ? assetNode.getNode("renditions")
                        : assetNode.addNode("renditions", "nt:folder");
            }
            
            LOG.info("renditionsNode :" + renditionsNode.getPath());

            Node renditionFile = renditionsNode.addNode("expanded.jpg", "nt:file");

            Node contentNode = renditionFile.addNode("jcr:content", "nt:resource");

            contentNode.setProperty("jcr:mimeType", mimeType);
            contentNode.setProperty("jcr:lastModified", Calendar.getInstance());
            contentNode.setProperty("jcr:data", new ByteArrayInputStream(imageBytes));

            contentNode.save();

            //adminResolver.adaptTo(Session.class).save();

        } catch (Exception e) {
            LOG.error("Failed to save rendition for asset: " + assetPath, e);
        }
    }

    public Resource getFolderOfImageFromRendition(String renditionPath) {
        try {
            // Step 1: Get the asset resource
            Resource assetResource = adminResolver.getResource(renditionPath);

            Asset asset = DamUtil.resolveToAsset(assetResource);

            String mainassetPath = asset.getPath();

            if (mainassetPath == null) {
                return null;
            }

            // Get the asset resource and then its parent folder resource
            Resource mainassetResource = adminResolver.getResource(mainassetPath);


            if (mainassetResource == null) {
                return null;
            }

            // Step 2: Get parent folder resource (up two levels from jcr:content/renditions/original etc.)
            Resource folderResource = mainassetResource.getParent();
            return folderResource;
        } catch (Exception e) {
            LOG.error("Failed to get folder from rendition path: " + renditionPath, e);
            return null;
        }
    }

    public String readPropertyFromFolder(Resource folderResource, String propertyName) {
        try {
            // Step 3: Get folder metadata node (e.g., /content/dam/myfolder/jcr:content/metadata)
            Resource metadataResource = folderResource.getChild("jcr:content/metadata");
            if (metadataResource == null) {
                return null;
            }

            // Step 4: Read folder schema properties
            ValueMap metadataProps = metadataResource.getValueMap();
            if (metadataProps == null) {
                return null;
            } else {
                String metadataProperty = metadataProps.get(propertyName, String.class);
                return metadataProperty;
            }
        } catch (Exception e) {
            LOG.error("Failed to read property from folder: " + propertyName, e);
            return null;
        }
    }
}