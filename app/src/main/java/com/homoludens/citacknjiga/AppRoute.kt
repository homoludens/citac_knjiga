package com.homoludens.citacknjiga

public sealed interface AppRoute {
    public val path: String

    public data object Start : AppRoute {
        override val path: String = "start"
    }

    public data object Library : AppRoute {
        override val path: String = "library"
    }

    public data object Import : AppRoute {
        override val path: String = "import"
    }

    public data object Synthesize : AppRoute {
        override val path: String = "synthesize"
    }

    public data object Player : AppRoute {
        override val path: String = "player"
    }

    public data object Settings : AppRoute {
        override val path: String = "settings"
    }

    public data object Diagnostics : AppRoute {
        override val path: String = "diagnostics"
    }

    public data object Book : AppRoute {
        public const val argument: String = "bookId"
        override val path: String = "book/{$argument}"

        public fun forId(id: String): String = "book/$id"
    }

    public data object TextPreview : AppRoute {
        public const val argument: String = "bookId"
        override val path: String = "text-preview/{$argument}"

        public fun forId(id: String): String = "text-preview/$id"
    }
}
