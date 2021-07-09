package org.enso.searcher

/** The entry in the suggestions order database.
  *
  * @param id the suggestion id
  * @param prev previous suggestion id
  * @param next next suggestion id
  */
case class SuggestionOrderEntry(
  id: Long,
  prev: Option[Long] = None,
  next: Option[Long] = None
)
