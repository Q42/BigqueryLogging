# Deploy procedures
## GOOGLE API
* On the dashboard go to "Application Settings" and under "Cloud integration" click the button, add API project (or something, can't remember).
* Go to code.google.com/apis/console and under "Services" enable Bigquery and Google Cloud Storage.
* Under Billing click "enable Billing"
* Enable Billing by filling out all required forms.

## ACCESS RIGHTS
* The default service account \<app_name\>@appspot.gserviceaccount.com should be given at least developer/write rights to the following:
* !NOTE! the following access rights should be granted in EXACTLY this order (google has issues too).
  * Add the service account as developer to the team in appengine dashboard "Permissions".
  * ACL for the target cloud storage bucket. 
    * Use gsutils and a bucket.acl for this, see the "On ACL and buckets" section in the readme. 
    * OR
    * Click on the checkbox before the bucket name in the cloud storage console. Click "Bucket permissions" and add the service account as writer.
  * Add the service account as "can edit" through "Teams" from the bucket screen in cloud.google.com/console. (Ask Coen).

## TASK QUEUES
* The task queue for huelogging is managed by queue.xml in the main project.  
* queue.xml is ignored, if you want to force a queue update, copy the main queue.xml into this project and deploy. Afterwards, delete again!
* Make sure queue.xml contains:  

    ```xml
    <queue>
       <target>bigquery-logging</target>
       <name>bigquery-logging-queue</name>
       <rate>20/s</rate>
       <bucket-size>40</bucket-size>
       <max-concurrent-requests>10</max-concurrent-requests>
       <retry-parameters>
          <task-retry-limit>5</task-retry-limit>
       </retry-parameters>
    </queue>
    ```

## CRON JOBS
* The cron job for huelogging is managed by cron.xml in the main project.
* cron.xml is ignored, if you want to force a cron update, copy the main cron.xml into this project and deploy. Afterwards, delete again!
* Add any additional crons that are needed (when you want to log another version than the main one for instance).
* Make sure the \<target/\> element has the same value as \<version/\> in appengine-web.xml.
* cron.xml should contain the following:  

    ```xml
    <cron>
       <url>/logging/logExportCron</url>
       <description>Export application logs to Bigquery</description>
       <schedule>every 2 minutes</schedule><!-- DEFAULT 2 mins -->
       <target>bigquery-logging</target>
    </cron>
    ```

## Configuration
* Check appengine-web.xml, make sure the version is as expected and all system-properties are set for the targeted environment.
* When all bigquerylogging.defaults are set and correct the default cron should not need any GET params.

## FINALLY
* run mvn appengine:update to deploy!