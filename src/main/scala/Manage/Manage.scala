package OLK.Manage

import Chisel._
import scala.collection.mutable.ArrayBuffer

/** Manage
  This file pipelines input so that it will match up for the alpha stage
  parameter p : pipeline stages

  input forceNotAdd = 1 Bit // This is used for queries
  // Optional Inputs
  input y        = (OLKn: 0, OLKc: 1 Bit, OLKr: SFix)
  input e  = Constant (OLKr Only)

  output forceNotAddOut = 1Bit
  output yOut    = (OLKn: 0, OLKc: 1 Bit, OLKr: 0)
  output yPosOut = (OLKn: 0, OLKc: 0, OLKr: SFix)
  output yNegOut = (OLKn: 0, OLKc: 0, OLKr: SFix)

  Registers:
  forceNAReg_0, forceNAReg_1, ... forceNAReg_(p-1)
  yReg_0, yReg_1, ... yReg_(p-2) (OLKr and OLKc Only)
  yPos, YNeg (OLKr Only)

  forceNAReg_0 = forceNotAdd
  forceNAReg_1 = forceNAReg_0
  forceNAReg_2 = forceNAReg_1
  ...
  forceNotAddOut = forceNAReg_(p-1)
  
  OLKc:
      yReg_0 = y
      yReg_1 = yReg_0
      yReg_2 = yReg_1
      ...  
      yReg_(p-2) = yReg(p-3)
      yOut = yReg_(p-2)
  OLKr:
      yReg_0 = y
      yReg_1 = yReg_0
      yReg_2 = yReg_1
      ...  
      yReg_(p-3) = yReg(p-4)
      yPos =   yReg_(p-3) - e
      yNeg = - yReg_(p-3) - e
      yPosOut = yPos
      yNegOut = yNeg
  */
class IOBundle(bitWidth: Int, fracWidth : Int) extends Bundle {
  val forceNAin  = Bool(INPUT)
  val forceNAout = Bool(OUTPUT)
  val resetin    = Bool(INPUT)
  val resetout   = Bool(OUTPUT)

  val forgetin  = Fixed(INPUT, bitWidth, fracWidth)
  val forgetout = Fixed(OUTPUT, bitWidth, fracWidth)
}

// For NORMA
class NORMAIOBundle(bitWidth : Int, fracWidth : Int) extends IOBundle(bitWidth, fracWidth) {
  val eta    = Fixed(INPUT, bitWidth, fracWidth)
  val nu     = Fixed(INPUT, bitWidth, fracWidth)
  val etapos = Fixed(OUTPUT, bitWidth, fracWidth) // = eta
  val etaneg = Fixed(OUTPUT, bitWidth, fracWidth) // = -eta
  val etanu  = Fixed(OUTPUT, bitWidth, fracWidth) // = eta*nu
  val etanu1 = Fixed(OUTPUT, bitWidth, fracWidth) // = -eta*(1-nu)
}

// Only used for Regression
class NORMArIOBundle(bitWidth : Int, fracWidth : Int) extends NORMAIOBundle(bitWidth, fracWidth) {
  val yRegin  = Fixed(INPUT, bitWidth, fracWidth)
  val yRegout = Fixed(OUTPUT, bitWidth, fracWidth)
}

// Only used for Classification
class NORMAcIOBundle(bitWidth : Int, fracWidth : Int) extends NORMAIOBundle(bitWidth, fracWidth) {
  val yCin  = Bool(INPUT)
  val yCout = Bool(OUTPUT)
}

// For OLK
class OLKIOBundle(bitWidth : Int, fracWidth : Int) extends IOBundle(bitWidth, fracWidth) {
  val fracCin  = Fixed(INPUT, bitWidth, fracWidth)
  val fracCout = Fixed(OUTPUT, bitWidth, fracWidth)
}

// Only used for Regression
class OLKrIOBundle(bitWidth : Int, fracWidth : Int) extends OLKIOBundle(bitWidth, fracWidth) {
  val epsilon  = Fixed(INPUT, bitWidth, fracWidth)
  val yRegin   = Fixed(INPUT, bitWidth, fracWidth)
  val yepos    = Fixed(OUTPUT, bitWidth, fracWidth) // OLKr only = (y - epsilon)
  val yeneg    = Fixed(OUTPUT, bitWidth, fracWidth) // OLKr only = -(y + epsilon)
}

// Only used for Classification
class OLKcIOBundle(bitWidth : Int, fracWidth : Int) extends OLKIOBundle(bitWidth, fracWidth) {
  val yCin  = Bool(INPUT)
  val yCout = Bool(OUTPUT)
}

class Manage(val bitWidth : Int, val fracWidth : Int, val stages : Int,
  val isNORMA : Boolean, val appType : Int) extends Module {
  Predef.assert(stages > 1, "There must be atleast two stages in the Manage class")
  Predef.assert(appType == 1 || appType == 2 || appType == 3,
    "appType must be 1 (classification), 2 (Novelty) or 3 (Regression)")

  val ZERO = Fixed(0, bitWidth, fracWidth)

  val io = {
    if ( isNORMA ) {
      val etaposReg = Reg(init=ZERO)
      val etanegReg = Reg(init=ZERO)
      val etanuReg  = Reg(init=ZERO)
      val etanu1Reg = Reg(init=ZERO)

      val normaIO = {
        if ( appType == 1 ) {
          val res   = new NORMAcIOBundle(bitWidth, fracWidth)
          val yCReg = ShiftRegister(res.yCin, stages, Bool(true))
          res.yCout  := yCReg
          res
        } else if ( appType == 2 ) {
          new NORMAIOBundle(bitWidth, fracWidth)
        } else {
          val res  = new NORMArIOBundle(bitWidth, fracWidth)
          val yReg = ShiftRegister(res.yRegin, stages, Bool(true))
          res.yRegout := yReg
          res
        }
      }

      etaposReg      := normaIO.eta
      normaIO.etapos := etaposReg
      etanegReg      := -normaIO.eta
      normaIO.etaneg := etanegReg
      etanuReg       := normaIO.eta*normaIO.nu
      normaIO.etanu  := etanuReg
      etanu1Reg      := etanuReg - normaIO.eta // - eta*(1 - nu)
      normaIO.etanu1 := etanu1Reg

      normaIO
    } else {
      val fracCReg = Reg(init=ZERO)

      val olkIO = {
        if ( appType == 1 ) {
          val res   = new OLKcIOBundle(bitWidth, fracWidth)
          val yCReg = ShiftRegister(res.yCin, stages, Bool(true))
          res.yCout := yCReg
          res
        } else if ( appType == 2 ) {
          new OLKIOBundle(bitWidth, fracWidth)
        } else {
          val res  = new OLKrIOBundle(bitWidth, fracWidth)
          val yReg = ShiftRegister(res.yRegin, stages - 1, Bool(true))
          val yeposReg = Reg(init=ZERO)
          val yenegReg = Reg(init=ZERO)
          yeposReg  := yReg - res.epsilon
          yenegReg  := - res.epsilon - yReg
          res
        }
      }

      fracCReg       := olkIO.fracCin
      olkIO.fracCout := fracCReg

      olkIO
    }
  }

  // Common
  val forceReg = ShiftRegister(io.forceNAin, stages, Bool(true))
  val resetReg = Reg(init=Bool(true))
  val forgetReg = Reg(init=ZERO)

  io.forceNAout := forceReg

  resetReg    := io.resetin
  io.resetout := resetReg

  forgetReg    := io.forgetin
  io.forgetout := forgetReg
}

class ManageTests(c : Manage) extends Tester(c) {
  val cycles = 5*c.stages
  val r = scala.util.Random

  val forceNAexpect = new ArrayBuffer[Boolean]()
  val yCexpect      = new ArrayBuffer[Boolean]()
  val yRegexpect    = new ArrayBuffer[Int]()
  for (i <- 1 until c.stages){
    forceNAexpect += true
    yCexpect      += true
    yRegexpect    += 0
  }

  var etanuOld = 0
  for (i <- 0 until cycles) {
    val forceNAin  = (r.nextInt(2) == 1)
    val resetin    = (r.nextInt(2) == 1)

    val yCin      = (r.nextInt(2) == 1)
    val yRegin    = r.nextInt(1 << (c.bitWidth/2))
    val forgetin  = r.nextInt(1 << (c.bitWidth/2))

    // For NORMA
    val eta    = r.nextInt(1 << (c.bitWidth/2))
    val nu     = r.nextInt(1 << (c.bitWidth/2))

    // For OLK
    val fracCin  = r.nextInt(1 << (c.bitWidth/2))
    val epsilon  = r.nextInt(1 << (c.bitWidth/2))

    forceNAexpect += forceNAin
    yCexpect += yCin
    yRegexpect += yRegin

    poke(c.io.forceNAin, Bool(forceNAin).litValue())
    poke(c.io.resetin, Bool(resetin).litValue())
    poke(c.io.forgetin, BigInt(forgetin))

    if ( c.isNORMA ) {
      val normaIO = c.io.asInstanceOf[NORMAIOBundle]
      poke(normaIO.eta, BigInt(eta))
      poke(normaIO.nu,  BigInt(nu))
      if ( c.appType == 1 ) {
        val normacIO = normaIO.asInstanceOf[NORMAcIOBundle]
        poke(normacIO.yCin, Bool(yCin).litValue())
      }
      if ( c.appType == 3 ) {
        val normarIO = normaIO.asInstanceOf[NORMArIOBundle]
        poke(normarIO.yRegin, BigInt(yRegin))
      }
    } else {
      val olkIO = c.io.asInstanceOf[OLKIOBundle]
      poke(olkIO.fracCin, BigInt(fracCin))
      if ( c.appType == 1 ) {
        val olkcIO = olkIO.asInstanceOf[OLKcIOBundle]
        poke(olkcIO.yCin, Bool(yCin).litValue())
      }
      if ( c.appType == 3 ) {
        val olkrIO = olkIO.asInstanceOf[OLKrIOBundle]
        poke(olkrIO.epsilon, BigInt(epsilon))
        poke(olkrIO.yRegin,  BigInt(yRegin))
      }
    }

    step(1)

    if ( i > c.stages - 1 ) {
      expect(c.io.forceNAout, Bool(forceNAexpect(i)).litValue())
      expect(c.io.resetout, Bool(resetin).litValue())
      expect(c.io.forgetout, BigInt(forgetin))

      if ( c.isNORMA ) {
        val normaIO = c.io.asInstanceOf[NORMAIOBundle]
        expect(normaIO.etapos, BigInt(eta))
        expect(normaIO.etaneg, BigInt(-eta))
        expect(normaIO.etanu,  BigInt((eta*nu) >> c.fracWidth))
        expect(normaIO.etanu1, BigInt( etanuOld - eta ))

        if ( c.appType == 1 ) {
          val normacIO = normaIO.asInstanceOf[NORMAcIOBundle]
          expect(normacIO.yCout, Bool(yCexpect(i)).litValue())
        }
        if ( c.appType == 3 ) {
          val normarIO = normaIO.asInstanceOf[NORMArIOBundle]
          expect(normarIO.yRegout, BigInt(yRegexpect(i)))
        }
      } else {
        val olkIO = c.io.asInstanceOf[OLKIOBundle]
        expect(olkIO.fracCout, BigInt(fracCin))

        if ( c.appType == 1 ) {
          val olkcIO = olkIO.asInstanceOf[OLKcIOBundle]
          expect(olkcIO.yCout, Bool(yCexpect(i)).litValue())
        }
        if ( c.appType == 3 ) {
          val olkrIO = olkIO.asInstanceOf[OLKrIOBundle]
          expect(olkrIO.yepos, BigInt(yRegexpect(i) - epsilon))
          expect(olkrIO.yeneg, BigInt(- (yRegexpect(i) + epsilon)))
        }
      }
    }
    etanuOld = (eta*nu) >> c.fracWidth
  }
}
