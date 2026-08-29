package com.engine.tools

import io.aeron.archive.Archive
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.cluster.ClusteredMediaDriver
import io.aeron.cluster.ConsensusModule
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.concurrent.ShutdownSignalBarrier
import java.io.File

/**
 * A single-node cluster host for local development: media driver, archive and consensus module in
 * one process, which is what the engine's service container attaches to.
 *
 * Production runs three or five of these on separate machines (Design.md §1) and keeps the media
 * driver out of the engine binary so the zero-allocation profile holds. One node has no
 * redundancy and is only useful for trying the system out.
 */
fun runCluster(args: Args) {
    val base = args.optional("dir") ?: "build/cluster-host"
    val aeronDir = args.optional("aeron-dir") ?: "$base/driver"
    val archiveDir = args.optional("archive-dir") ?: "$base/archive"
    val clusterDir = args.optional("cluster-dir") ?: "$base/cluster"
    val host = args.optional("host") ?: "localhost"
    val fresh = !args.has("keep")

    // memberId,ingress,consensus,log,catchup,archiveControl
    val members = args.optional("members")
        ?: "0,$host:20110,$host:20220,$host:20330,$host:20440,$host:8010"

    println("cluster: aeronDir=$aeronDir archive=$archiveDir cluster=$clusterDir")
    println("cluster: members=$members")

    val driverContext = MediaDriver.Context()
        .aeronDirectoryName(aeronDir)
        .threadingMode(ThreadingMode.SHARED)
        .dirDeleteOnStart(fresh)
        .dirDeleteOnShutdown(fresh)
        .errorHandler { it.printStackTrace() }

    val archiveContext = Archive.Context()
        .aeronDirectoryName(aeronDir)
        .archiveDir(File(archiveDir))
        .controlChannel("aeron:udp?endpoint=$host:8010")
        .replicationChannel("aeron:udp?endpoint=$host:0")
        .recordingEventsEnabled(false)
        .threadingMode(ArchiveThreadingMode.SHARED)
        .deleteArchiveOnStart(fresh)
        .errorHandler { it.printStackTrace() }

    val consensusContext = ConsensusModule.Context()
        .aeronDirectoryName(aeronDir)
        .clusterDir(File(clusterDir))
        .clusterMemberId(0)
        .clusterMembers(members)
        .ingressChannel("aeron:udp?term-length=64k")
        .replicationChannel("aeron:udp?endpoint=$host:0")
        .deleteDirOnStart(fresh)
        .errorHandler { it.printStackTrace() }

    ClusteredMediaDriver.launch(driverContext, archiveContext, consensusContext).use {
        println("cluster: started, awaiting shutdown signal")
        ShutdownSignalBarrier().use { it.await() }
        println("cluster: shutdown signal received")
    }
}
