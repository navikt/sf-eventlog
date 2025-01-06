# sf-eventlog
App for å daglig laste ned valgte entiteter fra Salesforce EventLog og logge i vanlige eller sikre logger.

I EventType.kt finner du definisjonen av hvilke eventtyper som hentes ned, og hvilke felter som logges. Felter listet under sensitiveFields logges kun i sikre logger.

eventlog-fetch-job.yaml brukes som trigger for å utføre operasjonen hver morgen.