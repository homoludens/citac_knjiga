package com.homoludens.citacknjiga

public sealed interface AppRoute {
    public val path: String

    public data object Start : AppRoute {
        override val path: String = "start"
    }
}
