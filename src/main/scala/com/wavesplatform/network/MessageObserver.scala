package com.wavesplatform.network

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{Channel, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import monix.reactive.subjects.PublishSubject
import scorex.block.Block
import scorex.transaction.History
import scorex.transaction.History.BlockchainScore
import scorex.utils.ScorexLogging

@Sharable
class MessageObserver extends ChannelInboundHandlerAdapter with ScorexLogging {

  private val signatures = PublishSubject[(Channel, Signatures)]()
  private val blocks = PublishSubject[(Channel, Block)]()
  private val checkpoints = PublishSubject[(Channel, Checkpoint)]()
  private val blockchainScores = PublishSubject[(Channel, BlockchainScore)]()
  private val microblockInvs = PublishSubject[(Channel, MicroBlockInv)]()
  private val microblockResponses = PublishSubject[(Channel, MicroBlockResponse)]()

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case b: Block => blocks.onNext((ctx.channel(), b))
    case sc: History.BlockchainScore => blockchainScores.onNext((ctx.channel(), sc))
    case s: Signatures => signatures.onNext((ctx.channel(), s))
    case c: Checkpoint => checkpoints.onNext((ctx.channel(), c))
    case mbInv: MicroBlockInv => microblockInvs.onNext((ctx.channel(), mbInv))
    case mb: MicroBlockResponse => microblockResponses.onNext((ctx.channel(), mb))
    case _ => super.channelRead(ctx, msg)

  }
}

object MessageObserver {
  def apply(): (MessageObserver, ChannelObservable[Signatures], ChannelObservable[Block], ChannelObservable[BlockchainScore], ChannelObservable[Checkpoint], ChannelObservable[MicroBlockInv], ChannelObservable[MicroBlockResponse]) = {
    val mo = new MessageObserver()
    (mo, mo.signatures, mo.blocks, mo.blockchainScores, mo.checkpoints, mo.microblockInvs, mo.microblockResponses)
  }
}
