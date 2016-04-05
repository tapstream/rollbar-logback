package com.tapstream.rollbar;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

public class RollbarAppender extends UnsynchronizedAppenderBase<ILoggingEvent>{    

    private static final String ENV_VAR_APIKEY = "ROLLBAR_LOGBACK_API_KEY";

    private NotifyBuilder payloadBuilder;
    
    private URL url;
    private String apiKey;
    private String environment;
    private String rollbarContext;
    private boolean async = true;
    private IHttpRequester httpRequester = new HttpRequester();
    
    public RollbarAppender(){
        try {
            this.url = new URL("https://api.rollbar.com/api/1/item/");
        } catch (MalformedURLException e) {
            addError("Error initializing url", e);
        }
    }
    
    public void setHttpRequester(IHttpRequester httpRequester){
        this.httpRequester = httpRequester;
    }
 
    public void setUrl(String url) {
        try {
            this.url = new URL(url);
        } catch (MalformedURLException e) {
            addError("Error setting url", e);
        }
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }
    
    public void setAsync(boolean async){
        this.async = async;
    }
    
    public void setRollbarContext(String context){
        this.rollbarContext = context;
    }

    @Override
    public void start() {
        boolean error = false;

        try {
            String environmentApiKey = System.getenv(ENV_VAR_APIKEY);
            if(environmentApiKey != null){
                this.apiKey = environmentApiKey;
            }
        } catch(SecurityException e){
            addWarn("Access to environment variables was denied. ("+e.getMessage()+")");
        }

        if (this.url == null) {
            addError("No url set for the appender named [" + getName() + "].");
            error = true;
        }
        if (this.apiKey == null || this.apiKey.isEmpty()) {
            addError("No apiKey set for the appender named [" + getName() + "].");
            error = true;
        }
        if (this.environment == null || this.environment.isEmpty()) {
            addError("No environment set for the appender named [" + getName() + "].");
            error = true;
        }
   
        try {
            payloadBuilder = new NotifyBuilder(apiKey, environment, rollbarContext);
        } catch (JSONException e) {
            addError("Error building NotifyBuilder", e);
            error = true;
        }
        
        if (!error){
            super.start();
        }
        
    }

    @Override
    public void stop() {
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String levelName = event.getLevel().toString().toLowerCase();
        String message = event.getFormattedMessage();
        Map<String, String> propertyMap = event.getMDCPropertyMap();
        
        Throwable throwable = null;
        ThrowableProxy throwableProxy = (ThrowableProxy)event.getThrowableProxy();
        if (throwableProxy != null)
            throwable = throwableProxy.getThrowable();
        
        final JSONObject payload = payloadBuilder.build(levelName, message, throwable, propertyMap);
        final HttpRequest request = new HttpRequest(url, "POST");
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Accept", "application/json");
        request.setBody(payload.toString());
        
        if (async){
            getContext().getExecutorService().submit(new Runnable(){
                @Override
                public void run() {
                    sendRequest(request);
                }
            });
        } else {
            sendRequest(request);
        }
        
        
    }
    
    private void sendRequest(HttpRequest request){
        try {
            int statusCode = httpRequester.send(request);
            if (statusCode >= 200 && statusCode <= 299){
                // Everything went OK
            } else {
                addError("Non-2xx response from Rollbar: " + statusCode);
            }
            
        } catch (IOException e) {
            addError("Exception sending request to Rollbar", e);
        }
    }

}
