package com.example.guitarapp.ui.features.camera.hand_tracking

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [TabString::class], version = 1, exportSchema = false)
abstract class TabDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao

    companion object {
        @Volatile
        private var INSTANCE: TabDatabase? = null

        fun getDatabase(context: Context): TabDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TabDatabase::class.java,
                    "tab_database"
                )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)

                            INSTANCE?.let { database ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    database.tabDao().insertTabString(TabString(content = "4-3,3-3,_-0,_-0,1-2,2-3", name = "G Chord"))
                                    database.tabDao().insertTabString(TabString(content = "_-0,1-1,_-0,2-2,3-3,_-x", name = "C Chord"))
                                }
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}