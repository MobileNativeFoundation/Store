package com.atlas.db

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "users")
class UserEntity(@PrimaryKey val id: String, val name: String, val email: String)

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = ?") fun user(id: String): Flow<UserEntity?>
    @Upsert suspend fun upsert(row: UserEntity)
    @Query("DELETE FROM users WHERE id = ?") suspend fun delete(id: String)
    @Query("DELETE FROM users") suspend fun deleteAll()
}

@Database(entities = [UserEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
}
