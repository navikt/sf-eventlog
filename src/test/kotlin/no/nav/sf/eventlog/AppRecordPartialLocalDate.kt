package no.nav.sf.eventlog

import java.time.LocalDateTime

data class AppRecordPartialLocalDate(
    val CreatedDate: LocalDateTime,
    val Log_Level__c: String,
    val Application_Domain__c: String,
    val Source_Class__c: String,
    val Source_Function__c: String,
    val UUID__c: String
)
