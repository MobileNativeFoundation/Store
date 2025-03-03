package org.mobilenativefoundation.store.store5.util.fake

sealed class NotesKey {
  data class Single(val id: String) : NotesKey()

  data class Collection(val id: String) : NotesKey()
}
