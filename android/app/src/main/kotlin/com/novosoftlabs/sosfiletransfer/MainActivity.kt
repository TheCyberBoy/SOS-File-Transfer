package com.novosoftlabs.sosfiletransfer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.novosoftlabs.sosfiletransfer.ui.HomeScreen
import com.novosoftlabs.sosfiletransfer.ui.ReceiveScreen
import com.novosoftlabs.sosfiletransfer.ui.SendScreen
import com.novosoftlabs.sosfiletransfer.ui.theme.SosFileTransferTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SosFileTransferTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SosFileTransferApp()
                }
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val SEND = "send"
    const val RECEIVE = "receive"
}

@Composable
private fun SosFileTransferApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onSend = { navController.navigate(Routes.SEND) },
                onReceive = { navController.navigate(Routes.RECEIVE) },
            )
        }
        composable(Routes.SEND) {
            SendScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.RECEIVE) {
            ReceiveScreen(onBack = { navController.popBackStack() })
        }
    }
}
