package com.costacruise.core.servlets;

import java.io.IOException;
import java.util.Iterator;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.propertytypes.ServiceDescription;

import com.google.gson.JsonObject;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;

@Component(service = { Servlet.class })
@SlingServletResourceTypes(
        resourceTypes="srilanka-airlines/location-tag",
        methods=HttpConstants.METHOD_GET)
@ServiceDescription("Get Location Tag Servlet")
public class GetLocationTagServlet extends SlingSafeMethodsServlet {
  
  private static final long serialVersionUID = 1L;

  @Override
  protected void doGet(final SlingHttpServletRequest request, final SlingHttpServletResponse response) throws ServletException, IOException {
    // String userName = request.getParameter("userName");
    String userName = "bangaloreuser";
    String homePath = "/home/users";
    Resource homeResource = request.getResourceResolver().getResource(homePath);
    String cityValue = null;
    if (homeResource != null) {
      Iterator<Resource> firstLevelItr = homeResource.listChildren();
      while (firstLevelItr.hasNext()) {
        Resource firstLevelChild = firstLevelItr.next();
        Iterator<Resource> userItr = firstLevelChild.listChildren();
        while (userItr.hasNext()) {
          Resource userNode = userItr.next();
          String usr = userNode.getValueMap().get("rep:principalName", String.class);
          if (null!=usr && usr.equals(userName)) {
            Iterator<Resource> userNodeChildren = userNode.listChildren();
            while (userNodeChildren.hasNext()) {
              Resource child = userNodeChildren.next();
              if ("profile".equals(child.getName())) {
                cityValue = child.getValueMap().get("city", String.class);
                cityValue = "srilanka-airlines:" + cityValue.toLowerCase();
                break;
              }
            }
            break;
          }
        }
      }
    }
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("city", cityValue);
    response.setContentType("application/json");
    response.getWriter().write(jsonObject.toString());
  }
}

