package com.posthog

public enum class PostHogEventName(public val nameEvent:String){
    SNAPSHOT("\$snapshot"),
    PAGEVIEW("\$pageview"),
    PAGELEAVE("\$pageleave"),
    SET("\$set"),
    IDENTIFY("\$identify"),
    GROUP_IDENTIFY("\$groupidentify"),
    CREATE_ALIAS("\$create_alias"),
    CLIENT_INGESTION_WARNING("\$client_ingestion_warning"),
    WEB_EXPERIMENT_APPLIED("\$web_experiment_applied"),
    FEATURE_ENROLLMENT_UPDATE("\$feature_enrollment_update"),
    FEATURE_FLAG_CALLED("\$feature_flag_called"),
    SURVEY_DISMISSED("survey dismissed"),
    SURVEY_SENT("survey sent"),
    SURVEY_SHOWN("survey shown");

    public companion object{
        public fun isUnsafeEditable(name:String):Boolean{
            return values().firstOrNull { it.nameEvent == name } != null
        }
    }
}