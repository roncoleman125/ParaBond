package parabond.test

import parabond.mongo.MongoHelper
import parabond.mongo.MongoConnection
import parabond.entry.SimpleBond
import parabond.util.Helper
import parabond.value.SimpleBondValuator
import parabond.mongo.MongoDbObject
import scala.util.Random

/**
 * This class uses parallel collections to price n portfolios in the
 * parabond database using the fine-grain algorithm.
 * @author Ron Coleman
 */
class Par03 {
  /** Number of bond portfolios to analyze */
  val PORTF_NUM = 100
  
  /** Initialize the random number generator */
  val ran = new Random(0)   
  
  /** Write a detailed report */
  val details = false
  
  /** Record captured with each result */
  case class Result(id : Int, price: Double, bondCount: Int, t0: Long, t1: Long)
  
  case class Data(portfId: Int, bonds:List[SimpleBond], result: Result)
  
  def test {
    // Set the number of portfolios to analyze
    val arg = System.getProperty("n")
    
    val n = if(arg == null) PORTF_NUM else arg.toInt
    
    val me =  this.getClass().getSimpleName()
    val outFile = me + "-dat.txt"
    
    val fos = new java.io.FileOutputStream(outFile,true)
    val os = new java.io.PrintStream(fos)
    
    os.print(me+" "+ "N: "+n+" ")
    
    val details = if(System.getProperty("details") != null) true else false
    
    // Build the portfolio list    
    val inputs = for(i <- 0 until n) yield Data(ran.nextInt(100000)+1,null, null)   
    
    // Build the portfolio list
    val now = System.nanoTime  
    val outputs = inputs.par.map(priced) 
    val t1 = System.nanoTime
    
    // Generate the detailed output report
    if(details) {
      println("%6s %10.10s %-5s %-2s".format("PortId","Price","Bonds","dt"))
      
      outputs.foreach { output =>
        val id = output.result.id

        val dt = (output.result.t1 - output.result.t0) / 1000000000.0

        val bondCount = output.result.bondCount

        val price = output.result.price

        println("%6d %10.2f %5d %6.4f %12d %12d".format(id, price, bondCount, dt, output.result.t1 - now, output.result.t0 - now))
      }
    }
    
    val dt1 = outputs.foldLeft(0.0) { (sum,result) =>      
      sum + (result.result.t1 - result.result.t0)
      
    } / 1000000000.0
    
    val dtN = (t1 - now) / 1000000000.0
    
    val speedup = dt1 / dtN
    
    val numCores = Runtime.getRuntime().availableProcessors()
    
    val e = speedup / numCores
    
    os.println("dt(1): %7.4f  dt(N): %7.4f  cores: %d  R: %5.2f  e: %5.2f ".
        format(dt1,dtN,numCores,speedup,e))  
    
    os.flush
    
    os.close
    
    println(me+" DONE! %d %7.4f".format(n,dtN))       
  }
  
  def priced(input: Data): Data = {
    
    // Value each bond in the portfolio
    val t0 = System.nanoTime
    
    // Retrieve the portfolio 
    val portfId = input.portfId
    
    val portfsQuery = MongoDbObject("id" -> portfId)

    val portfsCursor = MongoHelper.portfCollection.find(portfsQuery)
    
    // Get the bonds in the portfolio
    val bids = MongoHelper.asList(portfsCursor,"instruments")
    
    val bondIds = for(i <- 0 until bids.size) yield Data(bids(i),null,null)
    
//    val bondIds = asList(portfsCursor,"instruments")
    
    val outputStage1 = bondIds.par.map { bondId =>
      // Get the bond from the bond collection
      val bondQuery = MongoDbObject("id" -> bondId.portfId)

      val bondCursor = MongoHelper.bondCollection.find(bondQuery)

      val bond = MongoHelper.asBond(bondCursor)   
      
      val valuator = new SimpleBondValuator(bond, Helper.curveCoeffs)

      val price = valuator.price
      
      new SimpleBond(bond.id,bond.coupon,bond.freq,bond.tenor,price)        
    } 
    
    val outputStage2 = outputStage1.par.reduce { (a: SimpleBond, b:SimpleBond) =>
      new SimpleBond(0,0,0,0,a.maturity+b.maturity)
    } 
    
    MongoHelper.updatePrice(input.portfId,outputStage2.maturity) 
    
    val t1 = System.nanoTime
    
    Data(input.portfId,null,Result(input.portfId,outputStage2.maturity,bondIds.size,t0,t1))
  }    
}