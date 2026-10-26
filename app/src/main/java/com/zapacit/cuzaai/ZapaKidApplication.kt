package com.zapacit.cuzaai

import android.app.Application

class ZapaKidApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
