package com.gemmafit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Surface
import com.gemmafit.ui.theme.TextHint
import com.gemmafit.ui.theme.TextSecondary

@Composable
fun ThreeDeeMini(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(140.dp)
            .background(Surface.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "3D View",
            color = TextHint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(8.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Dot(size = 6)
            BoxLine(width = 1, height = 12)
            BoxLine(width = 30, height = 2)
            Row {
                LimbColumn()
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxLine(width = 2, height = 24, isActive = true)
                    Text("COM", color = Blue, fontSize = 10.sp)
                    Spacer(Modifier.height(4.dp))
                    Row {
                        LimbColumn(height = 20)
                        Spacer(Modifier.width(6.dp))
                        LimbColumn(height = 20)
                    }
                }
                Spacer(Modifier.width(8.dp))
                LimbColumn()
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("COM: stable", color = Blue, fontSize = 8.sp)
    }
}

@Composable
private fun LimbColumn(height: Int = 18) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxLine(width = 1, height = height)
        Dot(size = 3)
    }
}

@Composable
private fun Dot(size: Int) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(size.dp)
            .background(TextSecondary, RoundedCornerShape(50)),
    )
}

@Composable
private fun BoxLine(width: Int, height: Int, isActive: Boolean = false) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .width(width.dp)
            .height(height.dp)
            .background(if (isActive) Green.copy(alpha = 0.5f) else TextHint),
    )
}
