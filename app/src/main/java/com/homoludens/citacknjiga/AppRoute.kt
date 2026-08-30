package com.homoludens.citacknjiga

public sealed interface AppRoute {
    public val path: String

    public data object Start : AppRoute {
        override val path: String = "start"
    }

    public data object Diagnostics : AppRoute {
        override val path: String = "diagnostics"
    }

    public data object Book : AppRoute {
        public const val argument: String = "bookId"
        override val path: String = "book/{$argument}"

        public fun forId(id: String): String = "book/$id"
    }
}
