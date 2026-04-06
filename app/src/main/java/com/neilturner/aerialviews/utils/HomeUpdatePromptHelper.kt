package com.neilturner.aerialviews.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.databinding.DialogUpdatePromptBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HomeUpdatePromptHelper {
    fun show(
        context: Context,
        currentVersion: String,
        updateInfo: UpdateInfo,
        onDownload: () -> Unit,
        onLater: () -> Unit,
    ): AlertDialog {
        val binding = DialogUpdatePromptBinding.inflate(LayoutInflater.from(context))

        binding.updatePromptBadge.text = context.getString(R.string.home_update_badge)
        binding.updatePromptAppName.text = context.getString(R.string.home_update_app_name)
        binding.updatePromptVersion.text = updateInfo.tagName.removePrefix("v")
        binding.updatePromptSummary.text =
            context.getString(
                R.string.home_update_summary,
                currentVersion,
                updateInfo.tagName.removePrefix("v"),
            )

        binding.updatePromptHighlightsLabel.text = context.getString(R.string.home_update_highlights)
        binding.updatePromptDate.text =
            SimpleDateFormat("MMMM yyyy", Locale.US).format(Date()).uppercase(Locale.US)
        binding.updatePromptNotes.text = formatReleaseNotes(context, updateInfo.releaseNotes)

        binding.updatePromptDownload.text = context.getString(R.string.home_update_download)
        binding.updatePromptLater.text = context.getString(R.string.home_update_later)
        binding.updatePromptVersionBadge.text = context.getString(R.string.home_update_version_stable)

        val dialog = AlertDialog.Builder(context).setView(binding.root).create()
        dialog.setOnCancelListener { onLater() }
        dialog.setCanceledOnTouchOutside(false)

        binding.updatePromptDownload.setOnClickListener {
            onDownload()
            dialog.dismiss()
        }
        binding.updatePromptLater.setOnClickListener {
            onLater()
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.80f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.72f).toInt(),
        )
        binding.updatePromptDownload.requestFocus()
        return dialog
    }

    private fun formatReleaseNotes(
        context: Context,
        releaseNotes: String,
    ): String {
        val formatted =
            releaseNotes
                .replace("\\n", "\n")
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    when {
                        line.startsWith("## ") -> line.removePrefix("## ").trim()
                        line.startsWith("# ") -> line.removePrefix("# ").trim()
                        line.startsWith("- ") -> "• ${line.removePrefix("- ").trim()}"
                        line.startsWith("* ") -> "• ${line.removePrefix("* ").trim()}"
                        line.startsWith("• ") -> line.trim()
                        else -> "• ${line.trim()}"
                    }
                }
                .joinToString("\n")
                .trim()

        return formatted.ifBlank { context.getString(R.string.home_update_empty_notes) }
    }
}