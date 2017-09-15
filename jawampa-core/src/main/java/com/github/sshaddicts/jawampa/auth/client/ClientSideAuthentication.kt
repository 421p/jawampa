package com.github.sshaddicts.jawampa.auth.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.sshaddicts.jawampa.WampMessages.AuthenticateMessage
import com.github.sshaddicts.jawampa.WampMessages.ChallengeMessage

interface ClientSideAuthentication {
    val authMethod: String
    fun handleChallenge(message: ChallengeMessage, objectMapper: ObjectMapper): AuthenticateMessage
}
