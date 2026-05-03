package com.stucray.limen.audit.events;

public record ClientSecretRotatedEvent(
    Long tenantId,
    String registeredClientId,
    Long actorUserId
) {}
