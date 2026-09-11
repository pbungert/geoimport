package com.pbungert.geoimport.desktop

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.pbungert.geoimport.desktop.cli.ImportCommand
import com.pbungert.geoimport.desktop.cli.TagCommand
import com.pbungert.geoimport.desktop.cli.TracksCommand
import com.pbungert.geoimport.desktop.cli.TracksList
import com.pbungert.geoimport.desktop.cli.TracksPull

class Geoimport : CliktCommand(name = "geoimport") {

    override fun help(context: Context) =
        "Import photos off a card and geotag them from a recorded GPS track."

    override val printHelpOnEmptyArgs = true

    override fun run() = Unit
}

fun main(args: Array<String>) = Geoimport()
    .subcommands(
        ImportCommand(),
        TagCommand(),
        TracksCommand().subcommands(TracksPull(), TracksList()),
    )
    .main(args)
