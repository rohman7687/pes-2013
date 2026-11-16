package com.pes2013.imgpatcher

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PesPlayer(
    var internalId: Int,
    var name: String,
    var overall: Int,
    var attack: Int,
    var defense: Int,
    var balance: Int,
    var stamina: Int,
    var topSpeed: Int
)

object PesDatabaseEditor {
    private const val PLAYER_RECORD_SIZE = 128

    fun parsePlayersFromBin(binBytes: ByteArray): List<PesPlayer> {
        val playerList = mutableListOf<PesPlayer>()
        val buffer = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN)
        
        var offset = 0
        while (offset + PLAYER_RECORD_SIZE <= binBytes.size) {
            buffer.position(offset)
            val id = buffer.int
            val atk = buffer.get().toInt() and 0xFF
            val def = buffer.get().toInt() and 0xFF
            val bal = buffer.get().toInt() and 0xFF
            val stam = buffer.get().toInt() and 0xFF
            val spd = buffer.get().toInt() and 0xFF
            
            playerList.add(
                PesPlayer(
                    internalId = id,
                    name = "Player ID: $id",
                    overall = ((atk + def + stam) / 3),
                    attack = atk,
                    defense = def,
                    balance = bal,
                    stamina = stam,
                    topSpeed = spd
                )
            )
            offset += PLAYER_RECORD_SIZE
        }
        return playerList
    }

    fun updatePlayerRecord(binBytes: ByteArray, playerIndex: Int, updated: PesPlayer): ByteArray {
        val buffer = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN)
        val offset = playerIndex * PLAYER_RECORD_SIZE
        
        buffer.position(offset)
        buffer.putInt(updated.internalId)
        buffer.put(updated.attack.toByte())
        buffer.put(updated.defense.toByte())
        buffer.put(updated.balance.toByte())
        buffer.put(updated.stamina.toByte())
        buffer.put(updated.topSpeed.toByte())
        
        return binBytes
    }
}
