# Atlas — user/session data layer requirements

Kotlin Multiplatform app: `shared/` (KMP), `app/` (Android, Compose), `iosApp/` (Swift via SKIE).
Build a shared data layer for user profiles and the auth session. `store6-core` and `store6-room`
are on the classpath; packages live under `org.mobilenativefoundation.store6.*`.

1. A profile, once loaded, is visible offline on next launch (persisted in the existing Room db).
2. Pull-to-refresh on the profile screen must hit the server.
3. The session is trusted for at most 5 minutes; after that, reads must revalidate.
4. Fetch failures retry 3 times with backoff.
5. Cap the cache at 50 users.
6. Sign-out removes all locally persisted user data immediately.
7. A push notification marks one user's profile stale without deleting it.
8. iOS consumes the same shared store from Swift.
