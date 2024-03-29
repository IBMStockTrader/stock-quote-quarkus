/*
       Copyright 2017-2019 IBM Corp All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.ibm.hybrid.cloud.sample.stocktrader.stockquote;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//CDI 1.2
import javax.inject.Inject;
//JSON-B (JSR 367).  This largely replaces the need for JSON-P
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

//mpFaultTolerance 1.1
import org.eclipse.microprofile.faulttolerance.Fallback;
//mpRestClient 1.0
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.cache.CacheResult;
import io.quarkus.redis.client.RedisClient;
import io.vertx.redis.client.Response;

import com.ibm.hybrid.cloud.sample.stocktrader.stockquote.client.APIConnectClient;
import com.ibm.hybrid.cloud.sample.stocktrader.stockquote.client.IEXClient;
import com.ibm.hybrid.cloud.sample.stocktrader.stockquote.json.Quote;


@Path("/stock-quote") 

/** This version of StockQuote talks to API Connect (which talks to api.iextrading.com) */
public class StockQuote {
	private static Logger logger = Logger.getLogger(StockQuote.class.getName());
	

  @Inject
	RedisClient redisClient;

	
	private boolean redisEnabled = false;

	private boolean initialized = false;

	private static final long MINUTE_IN_MILLISECONDS = 60000;
	private static final double ERROR       = -1;
	private static final String FAIL_SYMBOL = "FAIL";
	private static final String SLOW_SYMBOL = "SLOW";
	private static final long   SLOW_TIME   = 60000; //one minute
	private static final String TEST_SYMBOL = "TEST";
	private static final double TEST_PRICE  = 123.45;

	private long cache_interval = 60; //default to 60 minutes
	private SimpleDateFormat formatter = null;
	private static String iexApiKey = null;

	private @Inject @RestClient APIConnectClient apiConnectClient;
	private @Inject @RestClient IEXClient iexClient;

	public static void main(String[] args) {
		try {
			if (args.length > 0) {
				StockQuote stockQuote = new StockQuote();
				Quote quote = stockQuote.getStockQuote(args[0]);
				logger.info("$"+quote.getPrice());
			} else {
				logger.info("Usage: StockQuote <symbol>");
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}
	
	private void initializeApp() {  
		/* Example deployment yaml stanza:
		spec:
		  containers:
		  - name: stock-quote
		    image: ibmstocktrader/stock-quote:latest
		    env:
		      - name: REDIS_URL
		        valueFrom:
		          secretKeyRef:
		            name: redis
		            key: url
		      - name: CACHE_INTERVAL
		        valueFrom:
		          secretKeyRef:
		            name: redis
		            key: cache-interval
		    ports:
		      - containerPort: 9080
		    imagePullPolicy: Always
		*/
		if(initialized) {
			logger.info("Application already intitialized");
			return ;
		}
		initialized = true;
		logger.info("The application is initializing...");
		String redis_url = System.getenv("REDIS_URL");
		logger.info("Redis URL: " + redis_url);
		if(redis_url != null) redisEnabled = true;
		logger.info("Redisclient: " + redisClient);
		
		try {
			try {
				String cache_string = System.getenv("CACHE_INTERVAL");
				if (cache_string != null) {
					cache_interval = Long.parseLong(cache_string);
				}
			} catch (Throwable t) {
				logger.warning("No cache interval set - defaulting to 60 minutes");
			}
			formatter = new SimpleDateFormat("yyyy-MM-dd");
			logger.info("Initialization complete!");
		} catch (Throwable t) {
			logException(t);
		}
		
		String mpUrlPropName = APIConnectClient.class.getName() + "/mp-rest/url";
		String urlFromEnv = System.getenv("APIC_URL");
		if ((urlFromEnv != null) && !urlFromEnv.isEmpty()) {
			logger.info("Using API Connect URL from config map: " + urlFromEnv);
			System.setProperty(mpUrlPropName, urlFromEnv);
		} else {
			logger.info("API Connect URL not found from env var from config map, so defaulting to value in jvm.options: " + System.getProperty(mpUrlPropName));
		}

		mpUrlPropName = IEXClient.class.getName() + "/mp-rest/url";
		urlFromEnv = System.getenv("IEX_URL");
		if ((urlFromEnv != null) && !urlFromEnv.isEmpty()) {
			logger.info("Using IEX URL from config map: " + urlFromEnv);
			System.setProperty(mpUrlPropName, urlFromEnv);
		} else {
			logger.info("IEX URL not found from env var from config map, so defaulting to value in jvm.options: " + System.getProperty(mpUrlPropName));
		}
	
		iexApiKey = System.getenv("IEX_API_KEY");
		if ((iexApiKey == null) || iexApiKey.isEmpty()) {
			logger.warning("No API key provided for IEX.  If API Connect isn't available, fallback to direct calls to IEX will fail");
		}

    }


	@GET
	@Path("/")
	@Produces("application/json")
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	/**  Get all stock quotes in Redis.  This is a read-only operation that just returns what's already there, without any refreshing */
	public List<Quote> getAllCachedQuotes() {
		initializeApp();
		ArrayList<Quote> quotes = new ArrayList<Quote>();

		logger.info("get all redisAPI:" + redisClient);

		if ( redisEnabled && redisClient != null) { 
		 try {

			Response keys = redisClient.keys("*");
			for(Response r : keys) {
				String key = r.toString();
				logger.info("key:" + key);
				try {
					String cachedQuote = redisClient.get(key).toString();
					logger.info("cachedQuote:" + cachedQuote);
					Jsonb jsonb = JsonbBuilder.create();
					Quote quote = jsonb.fromJson(cachedQuote, Quote.class);
					quotes.add(quote);

				}
				catch(Exception ignoredInvalid){
					logger.info("ivalid:" + key);
				}
			}
			return quotes;
		} catch (Throwable t) {
			logException(t);
		}
		finally {
		}
	}
		 logger.info("empty quotes");
		 return quotes;
	}

	@GET
	@Path("/{symbol}")
	@Produces("application/json")
	@Fallback(fallbackMethod = "getStockQuoteViaIEX")
	//@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	/**  Get stock quote from API Connect */
	public Quote getStockQuote(@PathParam("symbol") String symbol) throws IOException {
		initializeApp();
		if (symbol.equalsIgnoreCase(TEST_SYMBOL)) return getTestQuote(TEST_SYMBOL, TEST_PRICE);
		if (symbol.equalsIgnoreCase(SLOW_SYMBOL)) return getSlowQuote();
		if (symbol.equalsIgnoreCase(FAIL_SYMBOL)) { //to help test Istio retry policies
			logger.info("Throwing a RuntimeException for symbol FAIL!");
			throw new RuntimeException("Failing as requested, since you asked for FAIL!");
		}

		Quote quote = null;
		if (redisEnabled && redisClient != null) {
			try {
		
			logger.info("Getting "+symbol+" from Redis");
			Response responseSymbol = redisClient.get(symbol);
			String cachedValue = null;
			if(responseSymbol != null) {
				cachedValue = responseSymbol.toString();
			}
			logger.info("cachedQuote:" + cachedValue);

  			if (cachedValue == null) { //It wasn't in Redis
				logger.info(symbol+" wasn't in Redis so we will try to put it there");
				quote = getStockQuoteViaAPI(symbol); //so go get it like we did before we'd ever heard of Redis
				logger.info("Got quote for "+symbol+" from API Connect");
				redisClient.set(Arrays.asList(symbol, quote.toString())); // Put in Redis so it's there next time we ask
				logger.info("Put "+symbol+" in Redis");
			} else {
				logger.info("Got this from Redis for "+symbol+": "+cachedValue);

				try {
					Jsonb jsonb = JsonbBuilder.create();
					quote = jsonb.fromJson(cachedValue, Quote.class);
				} catch (Throwable t4) {
					logger.info("Unable to parse JSON obtained from Redis.  Proceeding as if the quote was too stale.");
					logException(t4);
				}

				if (isStale(quote)) {
					logger.info(symbol+" in Redis was too stale");
					try {
						quote = getStockQuoteViaAPI(symbol); //so go get a less stale value
						logger.info("Got quote for "+symbol+" from API Connect");
						redisClient.set(Arrays.asList(symbol, quote.toString())); // Put in Redis so it's there next time we ask
						logger.info("Refreshed "+symbol+" in Redis");
					} catch (Throwable t) {
						logger.info("Error getting fresh quote; using cached value instead");
						logger.log(Level.WARNING, t.getClass().getName(), t);
					}
				} else {
					logger.info("Used "+symbol+" from Redis");
				}
			}

			logger.info("Completed getting stock quote - releasing Redis resources");

		} catch (Throwable t) {
			logException(t);
			
			//something went wrong using Redis.  Fall back to the old-fashioned direct approach
			try {
				quote = getStockQuoteViaAPI(symbol);
				logger.info("Got quote for "+symbol+" from API Connect");
			} catch (Throwable t2) {
				logException(t2);
				return getTestQuote(symbol, ERROR);
			}
		} 
			
		} else {
			//Redis not configured.  Fall back to the old-fashioned direct approach
			try {
				logger.warning("Redis URL not configured, so driving call directly to API Connect");
				quote = getStockQuoteViaAPI(symbol);
				logger.info("Got quote for "+symbol+" from API Connect");
			} catch (Throwable t3) {
				logException(t3);
				return getTestQuote(symbol, ERROR);
			}
		}

		return quote;
	}

	@CacheResult(cacheName = "quote")
	public Quote getStockQuoteViaAPI(String symbol) throws IOException {
		logger.warning("Call directly to API Connect - caching internally in quarkus");
		Quote quote = apiConnectClient.getStockQuoteViaAPIConnect(symbol);
		logger.warning("Quote from API Connect:" + quote.toString());
		return quote;
		
	}



	/** When API Connect is unavailable, fall back to calling IEX directly to get the stock quote */
	public Quote getStockQuoteViaIEX(String symbol) throws IOException {
		logger.info("Using fallback method getStockQuoteViaIEX");
		return iexClient.getStockQuoteViaIEX(symbol, iexApiKey);
	}

	private boolean isStale(Quote quote) {
		if (quote==null) return true;

		long now = System.currentTimeMillis();
		long then = quote.getTime();
		logger.info("now: "+now+" then: "+then);


		if (then==0) return true; //no time value present in quote
		long difference = now - then;

		String symbol = quote.getSymbol();
		logger.info("Quote for "+symbol+" is "+difference/((double)MINUTE_IN_MILLISECONDS)+" minutes old");

		return (difference > cache_interval*MINUTE_IN_MILLISECONDS); //cached quote is too old
    }

	private Quote getTestQuote(String symbol, double price) { //in case API Connect or IEX is down or we're rate limited
		Date now = new Date();
		String today = formatter.format(now);

		logger.info("Building a hard-coded quote (bypassing Redis and API Connect");

		Quote quote = new Quote(symbol, price, today);

		logger.info("Returning hard-coded quote: "+quote!=null ? quote.toString() : "null");

		return quote;
	}

	private Quote getSlowQuote() { //to help test Istio timeout policies; deliberately not put in Redis cache
		logger.info("Sleeping for one minute for symbol SLOW!");

		try {
			Thread.sleep(SLOW_TIME); //to help test Istio timeout policies
		} catch (Throwable t) {
			logException(t);
		}

		logger.info("Done sleeping.");

		return getTestQuote(SLOW_SYMBOL, TEST_PRICE);
	}

	private static void logException(Throwable t) {
		logger.warning(t.getClass().getName()+": "+t.getMessage());

		//only log the stack trace if the level has been set to at least the specified level
		if (logger.isLoggable(Level.INFO)) {
			StringWriter writer = new StringWriter();
			t.printStackTrace(new PrintWriter(writer));
			logger.info(writer.toString());
		}
	}
}
