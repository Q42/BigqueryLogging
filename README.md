# READ ME
## MAVEN
Requires [Apache Maven](http://maven.apache.org) 3.0 or greater, and JDK 7+ in order to run.

To build, run

    mvn package

Building will run the tests, but to explicitly run tests you can use the test target

    mvn test

To start the app, use the [App Engine Maven Plugin](http://code.google.com/p/appengine-maven-plugin/) that is already included in this demo.  Just run the command.

    mvn appengine:devserver

For further information, consult the [Java App Engine](https://developers.google.com/appengine/docs/java/overview) documentation.

To see all the available goals for the App Engine plugin, run

    mvn help:describe -Dplugin=appengine

## DEPLOY
    
See DEPLOY.txt for deploy considerations

## GENERAL
### On ACL and buckets
If you haven't already, authorize gsutil to access your Google Cloud Storage account with:
```
$ gsutil config
```

Create a bucket with:  
```
$ gsutil mb gs://{bucket name}
```

Dump the bucket's ACL to a file:
```    
$ gsutil getacl gs://{bucket name} > bucket.acl
```

Add the following snippet before the first tag in bucket.acl:  
```xml
<Entry>
   <Scope type="UserByEmail">
      <EmailAddress>
      {your appid}@appspot.gserviceaccount.com
      </EmailAddress>
   </Scope>
   <Permission>
   WRITE
   </Permission>
</Entry>
```

Load the new ACL for the bucket:
```
$ gsutil setacl bucket.acl gs://{bucket name}
```

### Bigquery
The final name of the Bigquery table consists of the following parts: 
```
log_{SCHEMA HASH}_{START TIMESTAMP}_{END TIMESTAMP}
```