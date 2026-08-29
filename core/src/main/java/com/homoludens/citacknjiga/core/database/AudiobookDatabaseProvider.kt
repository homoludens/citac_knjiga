package com.homoludens.citacknjiga.core.database

import android.content.Context

/** Keeps Room implementation details inside core's public composition boundary. */
public fun createAudiobookDao(context: Context): AudiobookDao =
    AudiobookDatabase.create(context).audiobookDao()
