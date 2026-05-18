package com.spectra.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectra.app.ui.theme.HudColors

data class OnboardingPage(
    val title: String,
    val subtitle: String,
    val description: String
)

val onboardingPages = listOf(
    OnboardingPage(
        "SPECTRA",
        "AI-Powered Camera",
        "Professional photography intelligence built for the Samsung S24 Ultra. Every shot is analyzed and optimized in real-time."
    ),
    OnboardingPage(
        "SMART PRESETS",
        "8 Intelligent Modes",
        "Auto, Portrait, Night, Landscape, Action, Food, Macro, and Pro. Each mode configures ISO, shutter speed, and white balance for the scene."
    ),
    OnboardingPage(
        "AI COACHING",
        "Real-Time Guidance",
        "Get composition tips, lighting direction, horizon leveling, and lens recommendations while you frame your shot."
    ),
    OnboardingPage(
        "PRO MODE",
        "Full Manual Control",
        "Logarithmic ISO and shutter dials, RAW capture, focus peaking, zebra highlights, and live histogram. Total creative control."
    ),
    OnboardingPage(
        "READY",
        "Start Shooting",
        "Swipe presets at the bottom, tap coaching tips to apply them, and long-press the shutter for burst mode."
    )
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            OnboardingPageContent(onboardingPages[page])
        }

        // Page indicator dots
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(onboardingPages.size) { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == pagerState.currentPage) 10.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == pagerState.currentPage) HudColors.accent
                            else HudColors.accent.copy(alpha = 0.3f)
                        )
                )
            }
        }

        // Skip / Get Started button
        if (pagerState.currentPage == onboardingPages.size - 1) {
            Button(
                onClick = onComplete,
                colors = ButtonDefaults.buttonColors(containerColor = HudColors.accent),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .width(200.dp)
            ) {
                Text(
                    "GET STARTED",
                    color = Color.Black,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        } else {
            Text(
                text = "SKIP",
                color = HudColors.accent.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 40.dp, end = 24.dp)
                    .clickable { onComplete() }
            )
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = page.title,
            color = HudColors.accent,
            fontSize = 32.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = page.subtitle,
            color = HudColors.textSecondary,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = page.description,
            color = HudColors.textMuted,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}
