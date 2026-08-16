package com.atlas.api

class UserDto(val id: String, val name: String, val email: String)
class SessionDto(val token: String, val userId: String, val expiresAtEpochMillis: Long)

class AtlasApi {
    suspend fun getUser(id: String): UserDto = TODO("network call")
    suspend fun getSession(): SessionDto = TODO("network call")
}
