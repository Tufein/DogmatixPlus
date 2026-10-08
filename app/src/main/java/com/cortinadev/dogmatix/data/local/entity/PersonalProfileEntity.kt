package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personal_profile")
data class PersonalProfileEntity(@PrimaryKey val id: Int = 0, val activeId: String = "")
