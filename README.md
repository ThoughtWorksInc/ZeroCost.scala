# catz
**catz** is a collection of **cat**egorical data-structures that have **z**ero memory overhead.
All those data-structures are [opacity aliases](https://www.reddit.com/r/scala/comments/5qbdgq/value_types_without_anyval/dcxze9q/),
expose their features via static methods and [cats](https://typelevel.org/cats/) type classes.

Unlike [value classes](https://docs.scala-lang.org/overviews/core/value-classes.html), our *catz* types never boxes,
resulting in better performance and zero memory overhead, especially when using those types in tuples and collections.
