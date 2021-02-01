<!--
       Copyright 2017 IBM Corp All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

The *stock-quote* microservice gets the price of a specified stock.  It hits an API in **API Connect**,
which drives a call to `https://cloud.iexapis.com/stable/stock/{symbol}/quote` to get the actual data,
then mediates the structure of the returned JSON as described below.  Note that an API key needs to be
passed as a query param named `token` (the API Connect impl does that for you).

It responds to a `GET /{symbol}` REST request, where you pass in a stock ticker symbol, and it returns
a JSON object containing that *symbol*, the *price*, the *date* and the *time* it was quoted.  The *time*
field is the number of milliseconds since the start of 1970 (used in determining quote staleness).

For example, if you hit the `http://localhost:9080/stock-quote/IBM` URL, it would return
`{"date":"2021-01-15","price":129.05,"symbol":"IBM","time":1610721478309}`.

This service uses **Redis** for caching.  When a quote is requested, it first checks to see if it is
in the cache, and if so, whether it is less that an hour old, and if so, just uses that.  Otherwise
(or if any exceptions occur communicating with Redis), it drives the REST call to **API Connect** as
usual, then adds it to **Redis** so it's there for next time.

The *Java for Redis*, or **Jedis**, library is used for communicating with **Redis**.

This branch describes how the *stock-quote* can be deployed using Quarkus with Basic authentication for easier testing.

If you will be using your own version of Redis, make sure to import certificate and rebuild the image.

Import certificate using following command:

```
keytool -importcert -file redis-ca.pem -alias redis-ca -keystore mycerts3 -storepass changeit 
```

To build locally issue (you need docker runtime locally):

```
docker build --no-cache -t stock-quote-quarkus:latest .  
```

To run locally in docker issu:

```
docker run --rm -p 9080:9080 stock-quote-quarkus:latest
```

If you dont have docker locally you can build directly on OpenShift cluster:

```
oc apply -f manifests/build-img/is-quarkus-maven.yaml
oc apply -f manifests/build-img/is-stock-quote-quarkus.yaml
oc apply -f manifests/build-img/bc-stock-quote-quarkus.yaml
```

Once the image is build (it will take several minutes 10-20) you can deploy the the service.
Create required resources:

- Redis secret with redis url (update to match your database):
```
oc apply -f manifests/quarkus/secret-redis.yaml
```
- if using JWT, create config map:
```
oc apply -f manifests/quarkus/cm-jwt-config.yaml
```

Deploy either:

- using standard `Deployment`, `Service` and `Route` objects:
```
oc apply -f manifests/quarkus/deployment-stock-quote.yaml
```
- or using Knative Service:
```
oc apply -f manifests/quarkus/knative-stock-quote.yaml
```

Test deployed service adding following URI `/stock-quote/IBM` to the created route.


