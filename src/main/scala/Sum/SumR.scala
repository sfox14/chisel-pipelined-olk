package OLK.Sum

import Chisel._
import OLK._
import scala.collection.mutable.ArrayBuffer

/** SumR
  This function sums the results of the kernel function
  that are currently in the dictionary
  parameter d : dictionary size
  parameter s : stages

  input vi     = SFix[d]  // Outputs from kernel function
  input alphai = SFix[d] // Current weights of dictionary
  input addToDict = 1 Bit
  
  output sumR  = SFix
  output wD    = SFix
  output WD1   = SFix

  Registers:
  Stage_0, Stage_1, ... Stage_(s-1)[1]
  Spare_0[s+1], ... Spare_(s-1)[2]

  Logic:
  wi = vi.*alphai

  Stage_0[0:k] = sum(w1 : w(d-s-2)) + (addTODict) ? w(d-s-1) : 0
  Stage_1[0:l] = sum(Stage_0) + (addToDict) ? Spare_0[0] : 0
  Stage_2[0:m] = sum(Stage_1) + (addToDict) ? Spare_1[0] : 0
  ... etc where m < l < k

  Spare_0 = (addToDict) ? [w(d-s-2):w(d)] : [w(d-s-1):w(d-1)]
  Spare_1 = (addToDict) ? Spare_0[1:end]  : Spare_0[0:end-1]
  Spare_2 = (addToDict) ? Spare_1[1:end]  : Spare_1[0:end-1]
  ... etc

  sumR = Stage_(s-1)
  wD1  = Spare_(s-1)[0]
  wD   = Spare_(s-1)[1]
  */

class SumR(val bitWidth : Int, val fracWidth : Int, val dictionarySize : Int, val stages : ArrayBuffer[Boolean]) extends Module {
    def log2Dict : Int = { log2Up(dictionarySize) }
  def activeStages : Int = { stages.count(_ == true) }
  def increasesStage(x : Int) : Boolean = {
    if ( log2Up(x) == log2Up(x + 1) )
      false
    else
      true
  }
  def calculatedStages : Int = { (log2Dict + 1 + {
    var layerCount = dictionarySize - activeStages - 2
    var sum = 0
    var i = 0
    while ( layerCount > 1 ) {
      if ( stages(i) ) {
        if ( increasesStage(layerCount) )
          sum += 1
        layerCount += 1
      }
      layerCount = layerCount - (1 << (log2Up(layerCount) - 1))
      Predef.assert(i < stages.length - 1, "Stages array is not long enough") 
      i += 1
    }
    sum
  })} // Only adds a stage if moved up to higher power of 2
    Predef.assert(stages.length == calculatedStages, "Length of stages must be = " + (calculatedStages))

    def group[A](list : List[A], size : Int) : List[List[A]] = list.foldLeft( (List[List[A]](), 0) ) { (r, c) => 
        r match {
            case (head :: tail, num) =>
                if (num < size) ( (c :: head) :: tail, num + 1)
                else            ( List(c) :: head :: tail, 1)
            case (Nil, num) => (List(List(c)),1)
        }
    }._1.foldLeft(List[List[A]]())( (r,c) => c.reverse :: r)

    def buildLevel[A](list : List[A], op : (A, A) => A) : List[A] = group(list, 2).map(l => l.reduce[A](op(_,_)))

    def pipeline(wire : Fixed, hasStage : Boolean) = if (hasStage) Reg(init=Fixed(0, wire.getWidth(), wire.fractionalWidth), next=wire) else wire

    def pipeAdd(hasStage : Boolean)(a : Fixed, b : Fixed) : Fixed = pipeline(a + b, hasStage)

    val lnOf2 = scala.math.log(2)
    def log2(x : Int) : Int = (scala.math.log(x.toDouble) / lnOf2).toInt
    def log2(x : Double) : Int = (scala.math.log(x) / lnOf2).toInt

    def spareLevel(list : List[Fixed]) : Vec[Fixed] = Mux(io.addToDict, Vec(list.dropRight(1)), Vec(list.drop(1)))

    val ZERO = Fixed(0, bitWidth, fracWidth)

    val io = new Bundle {
        val vi  = Vec.fill(dictionarySize){Fixed(INPUT, bitWidth, fracWidth)}
        val alphai = Vec.fill(dictionarySize){Fixed(INPUT, bitWidth, fracWidth)}
        val addToDict = Bool(INPUT)

        val sumR = Fixed(OUTPUT, bitWidth, fracWidth)
        val wD = Fixed(OUTPUT, bitWidth, fracWidth)
        val wD1 = Fixed(OUTPUT, bitWidth, fracWidth)
    }

    /**
     * Initial Implementation
     */
    // Setup inputs as list to make it easier to work with
    val viList = io.vi.toList
    val alphaiList = io.alphai.toList

    // wi = vi.*alphai
    val wiList = (viList, alphaiList).zipped.map(_*_)
    val wiListPipe = wiList.map( x => { pipeline(x, stages(0)) } )

    val n = stages.length

    var sumrInput = wiListPipe.dropRight(activeStages + 2)
    var spareInput = wiListPipe.drop(dictionarySize - activeStages - 2)

    if ( stages(0) ) {
      sumrInput = sumrInput :+ pipeline(Mux(io.addToDict, ZERO, wiList(dictionarySize - activeStages - 2)), stages(0))
      spareInput = spareLevel( wiList.drop(dictionarySize - activeStages - 2)).toList.map( x => { pipeline(x, stages(0)) } )
    }

    val sumrTree = scala.collection.mutable.MutableList(sumrInput)
    val spareTree = scala.collection.mutable.MutableList(spareInput)

    // Build Adder Tree
    for (i <- 1 until n) {
        val shiftDict = Mux(io.addToDict, ZERO, spareTree.last.head)
        val adderLevel = {
          if ( stages(i - 1) )
            buildLevel(sumrTree.last.toList :+ shiftDict, pipeAdd(stages(i)))
          else
            buildLevel(sumrTree.last.toList, pipeAdd(stages(i)))
        }
        if (stages(i)) {
            print(spareTree.last.length + "\n")
            print(sumrTree.last.length + "\n")
            print(adderLevel.length + "\n")
            sumrTree += adderLevel :+ shiftDict
            val spare = spareLevel(spareTree.last.toList).toList
            spareTree += spare.map(x => { pipeline(x, stages(i)) } )
        } else {
            sumrTree += adderLevel
        }
    }
  printf("sumrTree\n")
  for (i <- 0 until sumrTree.length){
    printf("{")
    for (j <- 0 until sumrTree(i).length) {
      printf("%d, ", sumrTree(i)(j)) 
    }
    printf("}\n")
  }
  printf("spareTree\n")
  for (i <- 0 until spareTree.length){
    printf("{")
    for (j <- 0 until spareTree(i).length) {
      printf("%d, ", spareTree(i)(j)) 
    }
    printf("}\n")
  }
    // Output
    io.sumR := sumrTree.last.head
    io.wD := spareTree.last.last
    io.wD1 := spareTree.last.head
}

class SumRTests(c : SumR) extends Tester(c) {
    val lnOf2 = scala.math.log(2)
    def log2(x : Int) : Int = (scala.math.log(x.toDouble) / lnOf2).toInt
    def toFixed(x : Double, fracWidth : Int) : BigInt = BigInt(scala.math.round(x*scala.math.pow(2, fracWidth)))
    def toFixed(x : Float, fracWidth : Int) : BigInt = BigInt(scala.math.round(x*scala.math.pow(2, fracWidth)))
    def toFixed(x : Int, fracWidth : Int) : BigInt = BigInt(scala.math.round(x*scala.math.pow(2, fracWidth)))
    val r = scala.util.Random

  val activeStages = c.stages.count(_ == true)

  val cycles = 3*(activeStages + 2)

  val addToDicts = ArrayBuffer.fill(cycles + activeStages + 1){ false }//r.nextInt(2) == 1}
  val sumRAry = ArrayBuffer.fill(activeStages - 1){0}
  val wDAry   = ArrayBuffer.fill(activeStages - 1){0}
  val wD1Ary  = ArrayBuffer.fill(activeStages - 1){0}
  
  for (cyc <- 0 until cycles) {
    val inVI     = ArrayBuffer.fill(c.dictionarySize){r.nextInt(1 << c.fracWidth)}
    val inAlphaI = ArrayBuffer.fill(c.dictionarySize){r.nextInt(1 << c.fracWidth)}

    val wi = (inVI zip inAlphaI).map(pair => { (pair._1 * pair._2) >> c.fracWidth })
    print("wi(" + cyc + "): " + wi + "\n")
    val totalAdded = addToDicts.drop(cyc).take(activeStages).count(_ == true)

    sumRAry += wi.dropRight(totalAdded + 2).sum
    wDAry  += wi(wi.length - 1 - totalAdded)
    wD1Ary += wi(wi.length - 2 - totalAdded)

    poke(c.io.addToDict, Bool(addToDicts(cyc)).litValue())
    for (i <- 0 until c.dictionarySize) {
      poke(c.io.vi(i), BigInt(inVI(i)))
      poke(c.io.alphai(i), BigInt(inAlphaI(i)))
    }

    step(1)
    if (cyc >= activeStages - 1) {
      expect(c.io.sumR, BigInt(sumRAry(cyc)))
      expect(c.io.wD, BigInt(wDAry(cyc)))
      expect(c.io.wD1, BigInt(wD1Ary(cyc)))
    }
  }
}
