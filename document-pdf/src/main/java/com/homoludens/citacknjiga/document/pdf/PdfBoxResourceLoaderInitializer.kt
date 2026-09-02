package com.homoludens.citacknjiga.document.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** PdfBox's Android font/resource lookup is initialized once at the boundary. */
public object PdfBoxResourceLoaderInitializer {
    public fun initialize(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
    }
}
