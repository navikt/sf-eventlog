package no.nav.sf.eventlog.salesforce

data class AppRecordPartial(
    val CreatedDate: String,
    val Log_Level__c: String,
    val Application_Domain__c: String,
    val Source_Class__c: String,
    val Source_Function__c: String,
    val UUID__c: String
)
