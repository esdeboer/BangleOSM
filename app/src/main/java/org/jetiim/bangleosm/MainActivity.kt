package org.jetiim.bangleosm

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import mu.KotlinLogging
import net.osmand.aidlapi.info.AppInfoParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import org.json.JSONObject
import java.util.*

class MainActivity : AppCompatActivity(), OsmAndHelper.OnOsmandMissingListener {

    companion object {
        private const val PARAM_NT_DISTANCE = "turn_distance"
        private const val PARAM_NT_IMMINENT = "turn_imminent"
        private const val PARAM_NT_DIRECTION_NAME = "turn_name"
        private const val PARAM_NT_DIRECTION_TURN = "turn_type"
        private const val PARAM_NT_DIRECTION_ANGLE = "turn_angle"
    }

    private val logger = KotlinLogging.logger("nav")
    private var running = false
    private var turnNow = false
    private var turnIn = false
    private var previousFiredDistance: Int = 0
    private var routeLength: Int = 0
    private var currentDirectionInfo: ADirectionInfo? = null
    private var mAidlHelper: OsmAndAidlHelper? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mAidlHelper = OsmAndAidlHelper(this.application, this)
    }

    fun navigate(view : View) {
        val button = view.findViewById<Button>(R.id.button)
        if(button.text == "Navigate!") {
            button.text = "Stop Navigating"
            running = true
            fun looper() {
                if(running) {
                    info(mAidlHelper!!.appInfo)
                    val handler = Handler(Looper.getMainLooper())
                    handler.postDelayed({ looper() }, 10000)
                }
            }
            looper()
        } else {
            running = false
            button.text = "Navigate!"
            send(HashMap())
        }

    }

    private fun send(data: MutableMap<String?, Any?>) {
        val sendDataIntent = Intent("com.banglejs.uart.tx")
        val jsonData = JSONObject(data)
        logger.info(jsonData.toString())
        jsonData.put("t", "bangleOSM")
        sendDataIntent.putExtra("line", "\u0010GB($jsonData)\n")
        sendBroadcast(sendDataIntent)
    }

    private fun info(data: AppInfoParams) {

        fun isNewNavPoint(
            previousDirectionInfo: ADirectionInfo?, turnType: Int, distanceTo: Int
        ): Boolean {
            if (previousDirectionInfo == null ||
                previousDirectionInfo.turnType != turnType ||
                previousDirectionInfo.distanceTo < distanceTo - 5
            ) {
                turnIn = false
                turnNow = false
                return true
            }
            return false
        }


        val turnInfo = data.turnInfo
        if (turnInfo != null && turnInfo.size() > 0) {
            val previousDirectionInfo = currentDirectionInfo
            if(turnInfo.get("next_$PARAM_NT_DISTANCE") == null){
                logger.info(turnInfo.toString())
                currentDirectionInfo = ADirectionInfo(0, TurnType.OFFR,false)
                if(previousDirectionInfo == null || previousDirectionInfo.turnType != TurnType.OFFR) {
                    val data2: MutableMap<String?, Any?> = HashMap()
                    data2["turnType"] = TurnType.OFFR
                    send(data2)
                }
                return
            }
            val distanceTo = turnInfo.get("next_$PARAM_NT_DISTANCE") as Int
            val turnType1 = turnInfo.get("next_$PARAM_NT_DIRECTION_TURN") as String

            val turnType = TurnType.fromString(turnType1,false).value
            currentDirectionInfo = ADirectionInfo(distanceTo, turnType,false)

            logger.info("dist: $distanceTo")


            if(isNewNavPoint(previousDirectionInfo, turnType, distanceTo) ||
                previousFiredDistance - distanceTo > 100 ||
                (distanceTo < 100 && previousFiredDistance - distanceTo > 10) ||
                (distanceTo < 30 && previousFiredDistance - distanceTo > 5)) {

                previousFiredDistance = distanceTo

                val dataToSend: MutableMap<String?, Any?> = HashMap()
                dataToSend["eta"] = data.arrivalTime
                val distanceLeft  = data.leftDistance
                if (routeLength == 0) routeLength = distanceLeft

                dataToSend["distanceLeft"] = distanceLeft
                dataToSend["percentComplete"] = 100 - ((distanceLeft / routeLength.toDouble() ) * 100).toInt()

                val prefix = "current_"
                dataToSend["turnName"] = turnInfo.get("next_$PARAM_NT_DIRECTION_NAME")
                dataToSend["currentTurnName"] = turnInfo.get(prefix + PARAM_NT_DIRECTION_NAME)

                dataToSend["turnType"] = turnType
                dataToSend["distanceTo"] = distanceTo

                dataToSend["turnAngle"] = turnInfo.get(prefix + PARAM_NT_DIRECTION_ANGLE)
                dataToSend["nextTurnAngle"] = turnInfo.get("after_next$PARAM_NT_DIRECTION_ANGLE")
                dataToSend["distanceToNext"] = turnInfo.get("after_next$PARAM_NT_DISTANCE")
                dataToSend["afterNextTurnAngle"] = turnInfo.get("next_$PARAM_NT_DIRECTION_ANGLE")
                send(dataToSend)

                if(distanceTo < 30 && !turnIn) {
                    logger.info("nav update: < 24")
                    turnIn = true
                    val data2: MutableMap<String?, Any?> = HashMap()
                    data2["turnType"] = turnType
                    data2["turnIn"] = true
                    send(data2)
                }
                else if(distanceTo < 20 && !turnNow) {
                    logger.info("nav update: < 12")
                    turnNow = true
                    val data2: MutableMap<String?, Any?> = HashMap()
                    data2["turnType"] = turnType
                    data2["turnNow"] = true
                    send(data2)
                }
            }
        }
    }

    override fun osmandMissing() {
        logger.error("No OSM Installed")
    }

}