package com.streams.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.streams.app.AppState
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.RedGradient

/**
 * Cloud Storage tab: a single floating "+" button in the bottom-right corner (the
 * standard place for an upload action) that takes people to the subscription plans.
 */
@Composable
fun CloudScreen(nav: NavController) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "plusScale")

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
                .scale(scale)
                .size(60.dp)
                .shadow(12.dp, CircleShape, ambientColor = Red, spotColor = Red)
                .clip(CircleShape)
                .background(RedGradient)
                .clickable(interactionSource = interaction, indication = null) {
                    AppState.scrollToPlans.value = true
                    nav.navigate("profile") {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = "Upload", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}
