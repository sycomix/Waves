package com.wavesplatform.protobuf.events

import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.protobuf.block.{PBBlocks, PBMicroBlocks}
import com.wavesplatform.protobuf.events.StateUpdated.{BalanceUpdated => PBBalanceUpdated, LeasingUpdated => PBLeasingUpdated}
import com.wavesplatform.state.{BlockAdded, MicroBlockAdded, RollbackCompleted}

object PBEvents {
  import com.wavesplatform.protobuf.utils.PBInternalImplicits._

  // @todo deserialization to vanilla

  private def protobufStateUpdated(reason: (Short, Option[ByteStr]), su: VanillaStateUpdated): PBStateUpdated = {
    val reasonType = reason._1 match {
      case 0 => PBStateUpdated.ReasonType.BLOCK
      case 1 => PBStateUpdated.ReasonType.TRANSACTION
      case 2 => PBStateUpdated.ReasonType.MICROBLOCK
    }
    PBStateUpdated(
      reasonType = reasonType,
      reasonId = reason._2,
      balances = su.balances.map {
        case (addr, assetId, amt) =>
          PBBalanceUpdated(address = addr, amount = Some((assetId, amt)))
      },
      leases = su.leases.map {
        case (addr, leaseBalance) =>
          PBLeasingUpdated(address = addr, in = leaseBalance.in, out = leaseBalance.out)
      }
    )
  }

  def protobuf(event: VanillaBlockchainUpdated): PBBlockchainUpdated =
    event match {
      case BlockAdded(block, height, blockStateUpdate, transactionsStateUpdates) =>
        val txsUpdates = block.transactionData
          .map(_.id())
          .zip(transactionsStateUpdates)
          .map { case (id, su) => protobufStateUpdated((1, Some(id)), su) }

        val blockUpdate = protobufStateUpdated((0, Some(block.uniqueId)), blockStateUpdate)

        PBBlockchainUpdated(
          id = block.uniqueId,
          height = height,
          stateUpdates = Seq(blockUpdate) ++ txsUpdates,
          reason = PBBlockchainUpdated.Reason.Block(PBBlocks.protobuf(block))
        )
      case MicroBlockAdded(microBlock, height, microBlockStateUpdate, transactionsStateUpdates) =>
        val txsUpdates = microBlock.transactionData
          .map(_.id())
          .zip(transactionsStateUpdates)
          .map { case (id, su) => protobufStateUpdated((1, Some(id)), su) }

        val mbUpdate = protobufStateUpdated((2, Some(microBlock.totalResBlockSig)), microBlockStateUpdate)

        PBBlockchainUpdated(
          id = microBlock.totalResBlockSig,
          height = height,
          stateUpdates = Seq(mbUpdate) ++ txsUpdates,
          reason = PBBlockchainUpdated.Reason.MicroBlock(PBMicroBlocks.protobuf(microBlock))
        )
      case RollbackCompleted(to, height) =>
        PBBlockchainUpdated(
          id = to,
          height = height,
        )
    }
}