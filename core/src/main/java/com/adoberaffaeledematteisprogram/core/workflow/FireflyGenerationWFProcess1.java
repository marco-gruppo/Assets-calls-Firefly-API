package com.adoberaffaeledematteisprogram.core.workflow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

import javax.jcr.Node;
import javax.jcr.Session;

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

@Component(
    service = WorkflowProcess.class,
    property = {"process.label=Costa Cruises : Firefly Generation"}
)
public class FireflyGenerationWFProcess1 implements WorkflowProcess {

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
    public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
        String payloadPath = item.getWorkflowData().getPayload().toString();
        
        if (!payloadPath.startsWith("/content/dam/")) {
            throw new WorkflowException("Payload is not a DAM asset: " + payloadPath);
        }
        if(payloadPath.startsWith("/content/dam/costa-crociere/fire-fly-generation")){
        	
        	 LOG.info("payload path : "+payloadPath);
             Session adminSession;
             
	        try{
	            
	        	adminResolver = getServiceResolver();
	        	adminSession = adminResolver.adaptTo(Session.class);
	        	
	            // Step 1: Get Access Token
	            accessToken = fetchAccessToken();
	            
	            LOG.info("Binary File res path :"+payloadPath + "/jcr:content");
	            Resource assetBinRes = adminResolver.getResource(payloadPath + "/jcr:content");
	            LOG.info("Binary File res :"+assetBinRes);
	            
	            if (assetBinRes == null) {
	                throw new WorkflowException("Binary not found at: " + payloadPath);
	            }
	
	            Node node = assetBinRes.adaptTo(Node.class);
	            
	            try (InputStream inputStream = node.getProperty("jcr:data").getBinary().getStream()) {
	                byte[] imageBytes = IOUtils.toByteArray(inputStream);
	                
	                // Step 2: Upload Image to get uploadId
	                String uploadId = uploadImage(imageBytes);
	                Resource folderRes = getFolderOfImageFromRendition(payloadPath);
	                
	                String fireflyDimensions = readPropertyFromFolder(folderRes, "fireflyDimensions"); //1024x1024
	                
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
	
	                if(StringUtils.isNotBlank(height) && StringUtils.isNotBlank(width)) {
		                
	                	//Trigger Firefly expand UI on upload Id
		                //String statusMsg = triggerDeprecatedExpand(uploadId, payloadPath, Integer.parseInt(width), Integer.parseInt(height), accessToken);
		                
	                	//Trigger Firefly async expand UI on upload Id
		                String jobID = triggerAsyncExpand(uploadId, payloadPath, Integer.parseInt(width), Integer.parseInt(height), accessToken);
		                if (jobID == null) {
		                    LOG.error("FAILED TO START ASYNC EXPAND JOB");
		                }else {
			                // Poll until job is complete
			                String statusResponse = waitForCompletion(jobID, payloadPath);
			                LOG.info("STATUSRESPONSE :"+statusResponse);
		                }
	                }
	            }
	        } catch (Exception e) {
	            throw new WorkflowException("Workflow execution failed", e);
	        }
        }
    }

    private ResourceResolver getServiceResolver() throws Exception {
        return resolverFactory.getAdministrativeResourceResolver(null);
    }

    private String fetchAccessToken() throws IOException {
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
         }
    }

    private String uploadImage(byte[] imageBytes) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(UPLOAD_URL);
            post.setHeader("Authorization", "Bearer " + accessToken);
            post.setHeader("x-api-key", API_KEY);
            post.setHeader("Content-Type", "image/png");

            post.setEntity(new ByteArrayEntity(imageBytes, ContentType.create("image/png")));

            HttpResponse response = client.execute(post);
            String json = EntityUtils.toString(response.getEntity());
            JsonNode root = mapper.readTree(json);
            
            LOG.info("Response of upload call :"+ json);

            return root.get("images").get(0).get("id").asText();
        }
    }

    private String triggerDeprecatedExpand(String uploadId, String assetPath, int width, int height, String accessToken) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
        	
        	LOG.info("Access Token :"+ accessToken);
           
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
                	 if(response.getStatusLine().getStatusCode() == 200){
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
        }
    }
    
    
    private String triggerAsyncExpand(String uploadId, String assetPath, int width, int height, String accessToken) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
        	
        	LOG.info("Access Token :"+ accessToken);
        	
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
        	maskNode.set("source", maskSourceNode);
        	payload.set("mask", maskNode);

        	// Convert to string
        	String jsonString = mapper.writeValueAsString(payload);
        	
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            	            	
            	HttpPost postRequest = new HttpPost(ASYNC_EXPAND_URL);  
            	LOG.info("POST CALL TO "+ASYNC_EXPAND_URL);

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
                	 LOG.info("Status of Async Expand Call "+response.getStatusLine().getStatusCode());
                	 if(response.getStatusLine().getStatusCode() == 202){
                		 
                         String body = EntityUtils.toString(response.getEntity());
                     	 LOG.info("Async Call body : "+body);

                         return extractJobId(body);

                     } else {
                     	LOG.error("Failed to trigger expand :" + response.getStatusLine().getStatusCode());
                     	return "failure";
                     }
                	
                }
            }
        }
    }

    private String waitForCompletion(String operationId, String assetPath) throws Exception {
        String status = "";
        int maxAttempts = 3; // 60 minutes timeout (60 attempts * 60 seconds)
        int attempt = 0;
        while (attempt < maxAttempts) {
            LOG.info("Attempt number :"+attempt);

            try {
                Thread.sleep(20_000); // wait 1 minute before each check
                attempt++;

                String statusResponse = getJobStatus(operationId);
                status = extractStatus(statusResponse); 

                LOG.info("Current status for job {}: {} (attempt {}/{})", operationId, status, attempt, maxAttempts);

                if ("succeeded".equalsIgnoreCase(status)) {
                    JsonNode result = mapper.readTree(statusResponse);
                    
                    // Extract the image URL from the response
                    JsonNode outputs = result.get("outputs").get(0);
                    String finalImageURL = outputs.get("image").get("url").asText();
                    
                    // Download and save the expanded image
                    byte[] imageBytes = downloadImage(finalImageURL);
                    saveAsRendition(assetPath, imageBytes, "image/jpeg");                
                    
                    LOG.info("Job {} completed successfully", operationId);
                    return statusResponse;
                } else if ("failed".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)) {
                    LOG.error("Job failed for jobId:" + operationId + " with status: " + status);
                }
                // If status is "pending" or "processing", continue polling
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Polling interrupted for jobId: " + operationId, e);
            } catch (Exception e) {
                LOG.error("Error polling status for job {} (attempt {}/{}): {}", operationId, attempt, maxAttempts, e.getMessage());
                // Continue polling unless we've exhausted all attempts
                if (attempt >= maxAttempts - 1) {
                    throw new RuntimeException("Failed to get job status after " + maxAttempts + " attempts for jobId: " + operationId, e);
                }
            }
        }
        
        throw new RuntimeException("Job timeout after " + maxAttempts + " minutes for jobId: " + operationId);
    }
    
    private String extractStatus(String json) {
    	 
    	JsonNode result;
		try {
			result = mapper.readTree(json);
			return result.get("status").asText();
		} catch (IOException e) {
			 throw new RuntimeException("Error parsing status from Status call: " + json, e);
		}
    }
    
    private String extractJobId(String json) {
    	String jobId = "";
		try {
	    	JsonNode result = mapper.readTree(json);
			
	    	 // Extract the image URL from the response
	         jobId = result.get("jobId").asText();
	         LOG.info("jobId : "+jobId);
			
		} catch (IOException e) {
			LOG.error("IOException in Response Parsing of Expand API", e);
		}
        return jobId;
    }
    
    private String getJobStatus(String operationId) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(ASYNC_STATUS_URL + operationId);
            get.setHeader("Accept", "application/json");
            get.setHeader("Content-Type", "application/json");
            get.setHeader("Authorization", "Bearer " + accessToken);
            
            try (CloseableHttpResponse response = client.execute(get)) {
                return EntityUtils.toString(response.getEntity());
            }
        }
    }

    private byte[] downloadImage(String url) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(url);
            HttpResponse response = client.execute(get);
            return IOUtils.toByteArray(response.getEntity().getContent());
        }
    }

    private void saveAsRendition(String assetPath, byte[] imageBytes, String mimeType) throws Exception {    		
    		
    		Resource assetRes = adminResolver.getResource(assetPath);
            if (assetRes == null) throw new RuntimeException("Asset not found: " + assetPath);

            Node assetNode = assetRes.adaptTo(Node.class);
            LOG.info("assetNode :"+assetNode.getPath());
            Node renditionsNode = assetNode.hasNode("renditions")
                    ? assetNode.getNode("renditions")
                    : assetNode.addNode("renditions", "nt:folder");
            LOG.info("renditionsNode :"+renditionsNode.getPath());
            
            Node renditionFile = renditionsNode.addNode("expanded.jpg", "nt:file");
            
            Node contentNode = renditionFile.addNode("jcr:content", "nt:resource");

            contentNode.setProperty("jcr:mimeType", mimeType);
            contentNode.setProperty("jcr:lastModified", Calendar.getInstance());
            contentNode.setProperty("jcr:data", new ByteArrayInputStream(imageBytes));

            contentNode.save();
                        
            //adminResolver.adaptTo(Session.class).save();
        
    }
    
    public Resource getFolderOfImageFromRendition(String renditionPath) {
    	
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
        
    }
        


    public String readPropertyFromFolder(Resource folderResource, String propertyName) {
        
        // Step 3: Get folder metadata node (e.g., /content/dam/myfolder/jcr:content/metadata)
        Resource metadataResource = folderResource.getChild("jcr:content/metadata");
        if (metadataResource == null) {
            return null;
        }

        // Step 4: Read folder schema properties
        ValueMap metadataProps = metadataResource.getValueMap();
        if (metadataProps == null) {
            return null;
        }else{
            String metadataProperty = metadataProps.get(propertyName, String.class);
            return metadataProperty;
        }
    }
}