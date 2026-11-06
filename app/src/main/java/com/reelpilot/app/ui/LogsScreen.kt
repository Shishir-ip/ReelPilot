package com.reelpilot.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelpilot.app.util.AppLog

@Composable
fun LogsScreen() {
    val lines by AppLog.lines.collectAsState()
    Text(
        "Activity log",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(16.dp)
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp)
    ) {
        items(lines) { line ->
            Text(
                line,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)
            )
        }
    }
}
