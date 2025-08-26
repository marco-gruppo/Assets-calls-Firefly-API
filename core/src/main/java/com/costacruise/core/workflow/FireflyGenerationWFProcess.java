package com.costacruise.core.workflow;

import com.adobe.cq.inbox.ui.InboxItem;
import com.adobe.granite.taskmanagement.TaskManager;
import com.adobe.granite.taskmanagement.TaskManagerFactory;
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import org.apache.sling.api.resource.LoginException;
import com.adobe.granite.taskmanagement.Task;


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
    private static final String SERVICE_USER = "read-write-service";
    private String accessToken = "";
    private static final ObjectMapper mapper = new ObjectMapper();
    private ResourceResolver resourceResolver;

    @Reference
    ResourceResolverFactory resourceResolverFactory;


    @Override
    public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) {

        try {
            resourceResolver = resourceResolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, "customserviceuser"));
        } catch (LoginException e) {
            LOG.error("Failed to get service resource resolver", e);
            return;
        }

        String payloadPath = item.getWorkflowData().getPayload().toString();
        String wfArgs = args.get("PROCESS_ARGS", String.class); // test=abc,test1=bcd

        // Parse workflow arguments to extract Firefly credentials
        String clientId = "";
        String clientSecret = "";
        String apiKey = "";
        String mdPath = "";
        List<String> missingCredentials = new ArrayList<>();
        
        if (StringUtils.isNotBlank(wfArgs)) {
            try {
                String[] keyValuePairs = wfArgs.split(",");
                for (String pair : keyValuePairs) {
                    if (StringUtils.isNotBlank(pair) && pair.contains("=")) {
                        String[] keyValue = pair.split("=", 2);
                        if (keyValue.length == 2) {
                            String key = keyValue[0].trim();
                            String value = keyValue[1].trim();
                            
                            switch (key.toLowerCase()) {
                                case "clientid":
                                    clientId = value;
                                    LOG.info("Using dynamic CLIENT_ID: " + clientId);
                                    break;
                                case "clientsecret":
                                    clientSecret = value;
                                    LOG.info("Using dynamic CLIENT_SECRET: " + clientSecret);
                                    break;
                                case "apikey":
                                    apiKey = value;
                                    LOG.info("Using dynamic API_KEY: " + apiKey);
                                    break;
                                case "mdpath":
                                    mdPath = value;
                                    LOG.info("Using dynamic MD_PATH: " + mdPath);
                                    break;
                                default:
                                    LOG.debug("Unknown workflow argument: " + key + "=" + value);
                                    break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error parsing workflow arguments: " + wfArgs, e);
                LOG.info("Falling back to default credentials");
            }
        }
        
        // Check for missing credentials and store them for response
        if (StringUtils.isBlank(clientId)) {
            missingCredentials.add("clientid");
        }
        
        if (StringUtils.isBlank(clientSecret)) {
            missingCredentials.add("clientsecret");
        }
        
        if (StringUtils.isBlank(apiKey)) {
            missingCredentials.add("apikey");
        }

        if (StringUtils.isBlank(mdPath)) {
            missingCredentials.add("mdpath");
        }
        
        // Store missing credentials in workflow metadata for external access
        if (!missingCredentials.isEmpty()) {
            sendInboxNotification(payloadPath, missingCredentials, item.getId());
            return;
        }

        if (!payloadPath.startsWith("/content/dam/")) {
            LOG.error("Payload is not a DAM asset: " + payloadPath);
            return;
        }
        if (payloadPath.startsWith(mdPath)) {

            LOG.info("payload path : " + payloadPath);
            Session adminSession;

            try {

                resourceResolver = getServiceResolver();
                if (resourceResolver == null) {
                    LOG.error("Failed to get service resolver");
                    return;
                }

                adminSession = resourceResolver.adaptTo(Session.class);
                if (adminSession == null) {
                    LOG.error("Failed to get admin session");
                    return;
                }
                LOG.info("Admin Session :" + adminSession);

                // Step 1: Get Access Token with dynamic credentials
                accessToken = fetchAccessToken(clientId, clientSecret);
                if (StringUtils.isBlank(accessToken)) {
                    LOG.error("Failed to fetch access token");
                    return;
                }

                LOG.info("Binary File res path :" + payloadPath + "/jcr:content/renditions/original/jcr:content");
                Resource assetBinRes = resourceResolver.getResource(payloadPath + "/jcr:content/renditions/original/jcr:content");
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

                    // Step 2: Upload Image to get uploadId with dynamic API key
                    String uploadId = uploadImage(imageBytes, apiKey);
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

                        //Trigger Firefly expand UI on upload Id with dynamic API key
                        String statusMsg = triggerDeprecatedExpand(uploadId, payloadPath, Integer.parseInt(width), Integer.parseInt(height), accessToken, apiKey);

                        //Trigger Firefly async expand UI on upload Id
                        //String statusURL = triggerAsyncExpand(uploadId, payloadPath, Integer.parseInt(width), Integer.parseInt(height), accessToken, apiKey);
                        if (statusMsg == null) {
                            LOG.error("FAILED TO START ASYNC EXPAND JOB");
                        } else {
                            // Poll until job is complete
                            // String statusResponse = waitForCompletion(statusMsg, payloadPath);
                            // LOG.info("STATUSRESPONSE :" + statusResponse);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Workflow execution failed", e);
            } finally {
                // Clean up resources
                if (resourceResolver != null && resourceResolver.isLive()) {
                    resourceResolver.close();
                    resourceResolver = null;
                }
            }
        }
    }

    private ResourceResolver getServiceResolver() {
        return resourceResolver;
    }

    private String fetchAccessToken(String clientId, String clientSecret) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {

            HttpPost post = new HttpPost(TOKEN_URL);
            post.setHeader("Content-Type", "application/x-www-form-urlencoded");
            String body = String.format("grant_type=client_credentials&client_id=%s&client_secret=%s&scope=openid,AdobeID,read_organizations,session,ff_apis,firefly_api",
                    clientId, clientSecret);
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

    private String uploadImage(byte[] imageBytes, String apiKey) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(UPLOAD_URL);
            post.setHeader("Authorization", "Bearer " + accessToken);
            post.setHeader("x-api-key", apiKey);
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

    private String triggerDeprecatedExpand(String uploadId, String assetPath, int width, int height, String accessToken, String apiKey) {
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
                postRequest.setHeader("x-api-key", apiKey);
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
                        saveAsRendition(assetPath, imageBytes, "image/jpeg", width, height);
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

    private String triggerAsyncExpand(String uploadId, String assetPath, int width, int height, String accessToken, String apiKey) {
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
                postRequest.setHeader("x-api-key", apiKey);
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

    private String waitForCompletion(String statusMsg, String assetPath, int width, int height) {
        String status = "";
        int maxAttempts = 3;
        int attempt = 0;
        while (attempt < maxAttempts) {
            LOG.info("Attempt number :" + attempt);

            try {
                Thread.sleep(20_000);
                attempt++;

                String statusResponse = getJobStatus(statusMsg); // null
                LOG.error("Job Status response : " + statusResponse +" From URL :"+statusMsg);
                if (statusResponse == null) {
                    LOG.error("Failed to get job status for statusURL: " + statusMsg);
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
                        // saveAsRendition(assetPath, imageBytes, "image/jpeg", width, height);
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
                LOG.error("Polling interrupted for jobId"+e.getMessage(), e);
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

            // Extract the image URL from the response
            statusUrl = result.get("statusUrl").asText();
            LOG.info("statusUrl : " + statusUrl);

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
            get.setHeader("Authorization", "Bearer " + accessToken);

            try (CloseableHttpResponse response = client.execute(get)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                LOG.info("Body of status call: " + responseBody);
                return responseBody;
            }
        } catch (Exception e) {
            LOG.error("Failed to get job status using URL"+statusUrl, e);
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

    private void saveAsRendition(String assetPath, byte[] imageBytes, String mimeType, int width, int height) {
        try {
            Resource assetRes = resourceResolver.getResource(assetPath);
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
            assetNodePath = assetNodePath + "/jcr:content/renditions/original/jcr:content";
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
                Resource renditionsResource = resourceResolver.getResource(renditionsPathStr);
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

            // Create node name with dimensions
            String nodeName = String.format("expanded-%dx%d.jpg", height, width);
            
            // Check if the rendition file already exists
            Node renditionFile;
            if (renditionsNode.hasNode(nodeName)) {
                renditionFile = renditionsNode.getNode(nodeName);
                LOG.info("Rendition file '{}' already exists, using existing node", nodeName);
            } else {
                renditionFile = renditionsNode.addNode(nodeName, "nt:file");
                LOG.info("Created new rendition file '{}'", nodeName);
            }

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
            Resource assetResource = resourceResolver.getResource(renditionPath);

            Asset asset = DamUtil.resolveToAsset(assetResource);

            String mainassetPath = asset.getPath();

            if (mainassetPath == null) {
                return null;
            }

            // Get the asset resource and then its parent folder resource
            Resource mainassetResource = resourceResolver.getResource(mainassetPath);


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
    
    /**
     * Send inbox notification for missing Firefly credentials
     */
    private void sendInboxNotification(String assetPath, List<String> missingCredentials, String workflowId) {
        try {
            if (resourceResolver != null) {

                TaskManager taskManager = resourceResolver.adaptTo(TaskManager.class);
                TaskManagerFactory taskManagerFactory = taskManager.getTaskManagerFactory();
            
                Task newTask = taskManagerFactory.newTask("Firefly Configuration Missing");
                newTask.setName("Firefly Configuration Missing");
                newTask.setContentPath(assetPath);
                newTask.setPriority(Task.Priority.HIGH);
                newTask.setDescription("Add Firefly credentials in the workflow arguments in the format clientid=value,clientsecret=value,apikey=value,mdpath=value");
                newTask.setInstructions("");
                newTask.setCurrentAssignee("administrators");
                taskManager.createTask(newTask);
            }
        } catch (Exception e) {
            LOG.error("Failed to send inbox notification", e);
        }
    }
}
