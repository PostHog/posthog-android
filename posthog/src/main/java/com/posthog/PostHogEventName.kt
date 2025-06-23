package com.posthog

public enum class PostHogEventName(public val nameEvent:String){
    SNAPSHOT("\$snapshot"),
    SET("\$set"),
    IDENTIFY("\$identify"),
    GROUP_IDENTIFY("\$groupidentify"),
    CREATE_ALIAS("\$create_alias"),
    FEATURE_FLAG_CALLED("\$feature_flag_called");

    public companion object{
        public fun isUnsafeEditable(name:String):Boolean{
            return values().firstOrNull { it.nameEvent == name } != null
        }
    }
}