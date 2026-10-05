package com.cortinadev.dogmatix.ui.screens.cloud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.EmptyState

/** Settings → Cloud. (Placeholder: the cloud hub is built in its own step.) */
@Composable
fun CloudScreen(navController: NavController) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(title = stringResource(R.string.nav_cloud), message = "", icon = R.drawable.ic_cloud)
    }
}
