package com.ultimatepool.game

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

private data class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float, val number: Int, var sunk: Boolean = false) {
    val radius get() = 0.034f
    val color: Int get() = when(number) {
        0 -> Color.WHITE
        1,9 -> Color.rgb(255,220,0); 2,10 -> Color.rgb(0,92,255); 3,11 -> Color.rgb(255,18,28)
        4,12 -> Color.rgb(180,35,235); 5,13 -> Color.rgb(255,105,0)
        6,14 -> Color.rgb(0,205,72); 7,15 -> Color.rgb(155,8,28)
        else -> Color.BLACK
    }
}

class PoolGameView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clickSamples: ShortArray = makeClickSamples()
    private val clickTrack = AudioTrack(
        AudioManager.STREAM_MUSIC, 48000, AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT, clickSamples.size * 2, AudioTrack.MODE_STATIC
    ).apply { write(clickSamples, 0, clickSamples.size) }

    private fun makeClickSamples(): ShortArray {
        val rate = 48000
        val count = (rate * 0.052f).toInt()
        return ShortArray(count) { i ->
            val t = i.toFloat() / rate
            val attack = exp(-t * 105f)
            val body = (sin(2.0 * PI * 1850.0 * t) * 0.48 +
                sin(2.0 * PI * 1120.0 * t) * 0.30 +
                sin(2.0 * PI * 2750.0 * t) * 0.12)
            val noise = if (i < rate * 0.003f) {
                // A tiny broadband transient creates the sharp initial "啪".
                (kotlin.random.Random.nextFloat() * 2f - 1f) * 0.34
            } else 0.0
            val envelope = exp(-t * 76f)
            ((body * envelope + noise * attack) * 11500.0)
                .toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun playClick() {
        try {
            if (clickTrack.playState == AudioTrack.PLAYSTATE_PLAYING) clickTrack.stop()
            clickTrack.reloadStaticData()
            clickTrack.play()
        } catch (_: IllegalStateException) {
            // Ignore transient audio-device interruptions; gameplay remains available.
        }
    }
    private val balls = mutableListOf<Ball>()
    private var widthF = 1f
    private var heightF = 1f
    private var aimX = 1f
    private var aimY = 0f
    private var power = 0.55f
    private var charging = false
    private var message = "拖动瞄准，松手击球"
    private var playerTurn = true
    private var aiThinking = false
    private var lastFrame = System.nanoTime()
    private val table = RectF()
    private var rackWidth = 0f
    private var rackHeight = 0f
    private val pockets = arrayOf(floatArrayOf(0f,0f),floatArrayOf(.5f,0f),floatArrayOf(1f,0f),floatArrayOf(0f,1f),floatArrayOf(.5f,1f),floatArrayOf(1f,1f))

    init { resetRack(); isFocusable = true }

    private fun resetRack() {
        balls.clear()
        balls.add(Ball(.24f,.50f,0f,0f,0))
        // Regulation-style 15-ball rack: five rows, equal ball spacing and 60-degree sides.
        // The 8-ball is centered; the two rear corners contain one solid and one stripe.
        val order = listOf(1,9,2,3,8,10,11,4,12,5,6,13,7,14,15)
        rackWidth = table.width(); rackHeight = table.height()
        val minSide = min(table.width().takeIf { it > 1f } ?: 720f,
                          table.height().takeIf { it > 1f } ?: 360f)
        val radiusPx = minSide*.034f
        val rowStepX = sqrt(3f)*radiusPx
        val rowStepY = 2f*radiusPx
        val apexX = .79f
        var k = 0
        for (row in 0..4) for (col in 0..row) {
            val px = apexX - row*rowStepX/(table.width().takeIf { it > 1f } ?: 720f)
            val py = .50f + (col-row/2f)*rowStepY/(table.height().takeIf { it > 1f } ?: 360f)
            balls.add(Ball(px,py,0f,0f,order[k++]))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        widthF = width.toFloat(); heightF = height.toFloat()
        canvas.drawColor(Color.rgb(17,22,29))
        val mx = widthF*.09f; val my = heightF*.13f
        table.set(mx,my,widthF-mx,heightF-my)
        if (rackWidth <= 1f || abs(rackWidth-table.width()) > 1f || abs(rackHeight-table.height()) > 1f) resetRack()
        drawRoom(canvas); drawTable(canvas); drawBalls(canvas); drawAim(canvas); drawHud(canvas)
        val now = System.nanoTime()
        val dt = ((now-lastFrame)/1e9).toFloat().coerceIn(0f,.025f)
        lastFrame = now
        val moving = updatePhysics(dt)
        if (moving || (aiThinking && !isMoving())) postInvalidateOnAnimation()
        if (aiThinking && !isMoving()) { aiThinking=false; aiShot(); postInvalidateOnAnimation() }
    }

    private fun drawRoom(c: Canvas) {
        paint.shader=LinearGradient(0f,0f,0f,heightF,Color.rgb(42,48,59),Color.rgb(9,12,17),Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,widthF,heightF,paint); paint.shader=null
        paint.shader=LinearGradient(table.left,table.top,table.right,table.bottom,Color.rgb(255,211,112),Color.rgb(154,75,24),Shader.TileMode.CLAMP)
        c.drawRoundRect(table.left-22,table.top-22,table.right+22,table.bottom+22,30f,30f,paint); paint.shader=null
        paint.color=Color.rgb(255,231,166); c.drawRoundRect(table.left-15,table.top-15,table.right+15,table.bottom+15,24f,24f,paint)
        paint.color=Color.rgb(104,48,20); c.drawRoundRect(table.left-10,table.top-10,table.right+10,table.bottom+10,18f,18f,paint)
    }

    private fun drawTable(c: Canvas) {
        paint.shader=LinearGradient(table.left,table.top,table.right,table.bottom,Color.rgb(20,218,132),Color.rgb(0,112,70),Shader.TileMode.CLAMP)
        c.drawRoundRect(table,12f,12f,paint); paint.shader=null
        paint.color=Color.rgb(150,255,210); paint.style=Paint.Style.STROKE; paint.strokeWidth=3f
        c.drawRoundRect(table,12f,12f,paint); paint.style=Paint.Style.FILL
        for(p in pockets) {
            val x=table.left+p[0]*table.width(); val y=table.top+p[1]*table.height()
            paint.color=Color.rgb(2,4,7); c.drawCircle(x,y,ballPx()*1.82f,paint)
            paint.color=Color.argb(150,0,0,0); c.drawCircle(x,y,ballPx()*2.02f,paint)
            paint.color=Color.argb(95,255,255,255); paint.style=Paint.Style.STROKE; paint.strokeWidth=2f
            c.drawCircle(x,y,ballPx()*1.82f,paint); paint.style=Paint.Style.FILL
        }
        paint.color=Color.argb(110,230,220,170); paint.strokeWidth=1f
        c.drawLine(table.left+table.width()*.25f,table.top,table.left+table.width()*.25f,table.bottom,paint)
        paint.style=Paint.Style.STROKE; c.drawCircle(table.left+table.width()*.25f,table.centerY(),table.height()*.12f,paint); paint.style=Paint.Style.FILL
    }

    private fun ballPx() = min(table.width(),table.height())*.034f
    private fun toX(x:Float)=table.left+x*table.width()
    private fun toY(y:Float)=table.top+y*table.height()

    private fun drawBalls(c: Canvas) {
        val r = ballPx()
        for (b in balls.filter { !it.sunk }.sortedBy { it.y }) {
            val x = toX(b.x); val y = toY(b.y)
            paint.shader = RadialGradient(x, y + r*.55f, r*1.5f,
                intArrayOf(Color.argb(145,0,0,0), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawOval(x-r*1.25f,y+r*.35f,x+r*1.25f,y+r*1.45f,paint)
            paint.shader = null

            val base = b.color
            val dark = if (b.number == 0) Color.rgb(165,172,180) else Color.rgb(
                (Color.red(base)*.32f).toInt(), (Color.green(base)*.32f).toInt(), (Color.blue(base)*.32f).toInt())
            val bright = Color.rgb(
                (Color.red(base)+(255-Color.red(base))*.42f).toInt(),
                (Color.green(base)+(255-Color.green(base))*.42f).toInt(),
                (Color.blue(base)+(255-Color.blue(base))*.42f).toInt())
            paint.shader = RadialGradient(x-r*.36f,y-r*.43f,r*1.55f,
                intArrayOf(Color.WHITE,bright,base,dark),floatArrayOf(0f,.18f,.62f,1f),Shader.TileMode.CLAMP)
            c.drawCircle(x,y,r,paint); paint.shader=null

            if (b.number in 9..15) {
                val save=c.save()
                val clip=Path().apply { addCircle(x,y,r*.99f,Path.Direction.CW) }
                c.clipPath(clip)
                paint.shader=LinearGradient(x,y-r*.32f,x,y+r*.32f,
                    Color.rgb(255,255,255),Color.rgb(215,222,230),Shader.TileMode.CLAMP)
                c.drawRect(x-r,y-r*.32f,x+r,y+r*.32f,paint)
                paint.shader=null; c.restoreToCount(save)
            }
            paint.color=Color.argb(210,255,255,255); paint.style=Paint.Style.STROKE
            paint.strokeWidth=max(1.4f,r*.06f); c.drawCircle(x,y,r*.98f,paint); paint.style=Paint.Style.FILL
            paint.shader=RadialGradient(x-r*.39f,y-r*.47f,r*.42f,
                intArrayOf(Color.argb(245,255,255,255),Color.argb(0,255,255,255)),null,Shader.TileMode.CLAMP)
            c.drawCircle(x-r*.3f,y-r*.34f,r*.34f,paint); paint.shader=null

            // Broad softbox reflection and a thin bright rim give the balls a polished resin finish.
            val shineSave=c.save()
            c.clipPath(Path().apply { addCircle(x,y,r*.985f,Path.Direction.CW) })
            paint.shader=LinearGradient(x-r*.52f,y-r*.8f,x+r*.28f,y+r*.72f,
                intArrayOf(Color.argb(115,255,255,255),Color.argb(12,255,255,255),Color.TRANSPARENT),
                floatArrayOf(0f,.42f,1f),Shader.TileMode.CLAMP)
            c.drawOval(x-r*.72f,y-r*.88f,x+r*.08f,y+r*.72f,paint)
            paint.shader=null
            c.restoreToCount(shineSave)
            paint.color=Color.argb(125,255,255,255); paint.style=Paint.Style.STROKE
            paint.strokeWidth=max(1f,r*.035f)
            c.drawArc(x-r*.91f,y-r*.91f,x+r*.91f,y+r*.91f,205f,115f,false,paint)
            paint.style=Paint.Style.FILL

            if (b.number>0) {
                paint.color=Color.WHITE; c.drawCircle(x,y,r*.41f,paint)
                paint.color=Color.rgb(18,24,31); paint.textAlign=Paint.Align.CENTER
                paint.textSize=r*(if(b.number>=10) .48f else .58f)
                paint.typeface=Typeface.create("sans-serif",Typeface.BOLD)
                c.drawText(b.number.toString(),x,y+r*.19f,paint)
            }
        }
    }

    private fun drawAim(c: Canvas) {
        val cue=balls.firstOrNull{it.number==0 && !it.sunk} ?: return
        if(!playerTurn || isMoving()) return
        val x=toX(cue.x); val y=toY(cue.y)
        val len=hypot(aimX,aimY).coerceAtLeast(.001f)
        val ux=aimX/len; val uy=aimY/len
        val maxLen=table.width()*.72f
        val rayEndX=x+ux*maxLen; val rayEndY=y+uy*maxLen

        // Find the first object ball intersected by the cue-ball centre ray.
        var hit: Ball? = null
        var hitT = Float.MAX_VALUE
        for (b in balls) {
            if (b.number==0 || b.sunk) continue
            val bx=toX(b.x)-x; val by=toY(b.y)-y
            val projection=bx*ux+by*uy
            if(projection<=0f) continue
            val perpendicular2=bx*bx+by*by-projection*projection
            val combined=ballPx()*2f
            if(perpendicular2<=combined*combined) {
                val t=projection-sqrt(max(0f,combined*combined-perpendicular2))
                if(t<hitT) { hitT=t; hit=b }
            }
        }
        val cueEndX=if(hit!=null) x+ux*hitT else rayEndX
        val cueEndY=if(hit!=null) y+uy*hitT else rayEndY

        // Cue-ball path: bright white dashed line ending at the predicted contact.
        paint.color=Color.WHITE; paint.strokeWidth=4f
        paint.pathEffect=DashPathEffect(floatArrayOf(17f,9f),0f)
        c.drawLine(x+ux*ballPx(),y+uy*ballPx(),cueEndX,cueEndY,paint)
        paint.pathEffect=null
        paint.color=Color.rgb(255,235,70); c.drawCircle(cueEndX,cueEndY,ballPx()*.18f,paint)

        // Object-ball continuation: orange/yellow dashed trajectory after impact.
        if(hit!=null) {
            val hx=toX(hit.x); val hy=toY(hit.y)
            val nx=hx-x; val ny=hy-y; val nd=hypot(nx,ny).coerceAtLeast(.001f)
            val ox=nx/nd; val oy=ny/nd
            val objectEndX=hx+ox*table.width()*.48f
            val objectEndY=hy+oy*table.width()*.48f
            paint.color=Color.rgb(255,174,35); paint.strokeWidth=3.5f
            paint.pathEffect=DashPathEffect(floatArrayOf(11f,8f),0f)
            c.drawLine(hx+ox*ballPx(),hy+oy*ballPx(),objectEndX,objectEndY,paint)
            paint.pathEffect=null
            paint.color=Color.rgb(255,190,45); c.drawCircle(hx,hy,ballPx()*1.25f,paint)
            paint.color=Color.WHITE; paint.style=Paint.Style.STROKE; paint.strokeWidth=2f
            c.drawCircle(hx,hy,ballPx()*1.25f,paint); paint.style=Paint.Style.FILL
        }

        // Cue stick is positioned behind the cue ball.
        val back=ballPx()*2.7f+power*table.width()*.12f
        paint.color=Color.rgb(255,194,105); paint.strokeWidth=ballPx()*.38f
        c.drawLine(x-ux*back,y-uy*back,x-ux*ballPx()*1.15f,y-uy*ballPx()*1.15f,paint)
        paint.color=Color.rgb(255,245,215); paint.strokeWidth=2.5f
        c.drawLine(x-ux*back,y-uy*back,x-ux*back*.82f,y-uy*back*.82f,paint)
    }

    private fun drawHud(c: Canvas) {
        paint.color=Color.WHITE; paint.textSize=heightF*.045f
        paint.typeface=Typeface.create("sans-serif",Typeface.BOLD)
        paint.textAlign=Paint.Align.LEFT
        c.drawText("ULTIMATE POOL",table.left,table.top*.62f,paint)
        paint.textSize=heightF*.025f; paint.typeface=Typeface.DEFAULT
        c.drawText(if(playerTurn) "玩家回合" else "电脑回合",table.left,table.bottom+heightF*.075f,paint)
        paint.textAlign=Paint.Align.RIGHT
        c.drawText(message,table.right,table.bottom+heightF*.075f,paint)

        // Power bar: red -> amber -> green bands, with a moving level marker.
        val bx=table.left; val by=heightF*.94f; val bw=table.width()*.42f; val bh=max(12f,heightF*.018f)
        paint.color=Color.argb(160,0,0,0)
        c.drawRoundRect(bx-3f,by-3f,bx+bw+3f,by+bh+3f,bh*.5f,bh*.5f,paint)
        paint.shader=LinearGradient(bx,by,bx+bw,by,
            intArrayOf(Color.rgb(230,48,45),Color.rgb(255,183,42),Color.rgb(93,220,92)),
            floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        c.drawRoundRect(bx,by,bx+bw,by+bh,bh*.5f,bh*.5f,paint)
        paint.shader=null
        val markerX=bx+bw*power
        paint.color=Color.WHITE; paint.style=Paint.Style.STROKE; paint.strokeWidth=3f
        c.drawLine(markerX,by-5f,markerX,by+bh+5f,paint); paint.style=Paint.Style.FILL
        paint.color=Color.WHITE; paint.textAlign=Paint.Align.LEFT
        paint.textSize=heightF*.022f
        c.drawText("击球力度  "+(power*100).roundToInt()+"%",bx,by-9f,paint)
        paint.textAlign=Paint.Align.RIGHT
        c.drawText("低",bx+bw+4f,by+bh+heightF*.025f,paint)
        paint.textAlign=Paint.Align.LEFT
        c.drawText("高",bx+bw-18f,by+bh+heightF*.025f,paint)
    }

    private fun updatePhysics(dt:Float):Boolean {
        var moving=false
        val active=balls.filter{!it.sunk}
        for(b in active) {
            b.x+=b.vx*dt; b.y+=b.vy*dt
            val friction=exp(-2.4f*dt); b.vx*=friction; b.vy*=friction
            if(hypot(b.vx,b.vy)<.003f){b.vx=0f;b.vy=0f}
            val sxWall=table.width()/min(table.width(),table.height()).coerceAtLeast(1f)
            val syWall=table.height()/min(table.width(),table.height()).coerceAtLeast(1f)
            val rx=b.radius/sxWall; val ry=b.radius/syWall
            if(b.x<rx){b.x=rx;b.vx=abs(b.vx)*.88f}; if(b.x>1-rx){b.x=1-rx;b.vx=-abs(b.vx)*.88f}
            if(b.y<ry){b.y=ry;b.vy=abs(b.vy)*.88f}; if(b.y>1-ry){b.y=1-ry;b.vy=-abs(b.vy)*.88f}
            val sxPocket=table.width()/min(table.width(),table.height()).coerceAtLeast(1f)
            val syPocket=table.height()/min(table.width(),table.height()).coerceAtLeast(1f)
            for(p in pockets) if(hypot((b.x-p[0])*sxPocket,(b.y-p[1])*syPocket)<.055f) {
                if(b.number==0){b.x=.24f;b.y=.5f;b.vx=0f;b.vy=0f;message="母球落袋：手中球";playClick()} else { b.sunk=true; playClick() }
            }
            if(abs(b.vx)+abs(b.vy)>.001f) moving=true
        }
        for(i in active.indices) for(j in i+1 until active.size) {
            val a=active[i]; val b=active[j]; if(a.sunk||b.sunk) continue
            val sx=table.width()/min(table.width(),table.height()).coerceAtLeast(1f)
            val sy=table.height()/min(table.width(),table.height()).coerceAtLeast(1f)
            val dx=(b.x-a.x)*sx; val dy=(b.y-a.y)*sy
            val d=hypot(dx,dy); val minD=a.radius+b.radius
            if(d<minD && d>.00001f) {
                val nx=dx/d; val ny=dy/d; val overlap=minD-d
                a.x-=nx*overlap*.5f/sx; a.y-=ny*overlap*.5f/sy
                b.x+=nx*overlap*.5f/sx; b.y+=ny*overlap*.5f/sy
                val rel=((a.vx-b.vx)*sx)*nx+((a.vy-b.vy)*sy)*ny
                if(rel>0f){
                    a.vx-=rel*nx*.96f/sx; a.vy-=rel*ny*.96f/sy
                    b.vx+=rel*nx*.96f/sx; b.vy+=rel*ny*.96f/sy
                    playClick()
                }
            }
        }
        return moving
    }

    private fun isMoving()=balls.any{!it.sunk && hypot(it.vx,it.vy)>.003f}
    private fun shoot(dx:Float,dy:Float) {
        val cue=balls.firstOrNull{it.number==0 && !it.sunk} ?: return
        val len=hypot(dx,dy).coerceAtLeast(.001f)
        cue.vx=dx/len*(.65f+power*2.7f); cue.vy=dy/len*(.65f+power*2.7f)
        playClick()
        playerTurn=false
        postDelayed({
            if(!isMoving()) aiShot() else aiThinking=true
            postInvalidateOnAnimation()
        },350)
    }
    private fun aiShot() {
        val cue=balls.firstOrNull{it.number==0 && !it.sunk} ?: return
        val target=balls.firstOrNull{it.number!=0 && it.number!=8 && !it.sunk}
        if(target!=null) {
            val dx=target.x-cue.x; val dy=target.y-cue.y; val d=hypot(dx,dy).coerceAtLeast(.001f)
            cue.vx=dx/d*1.55f; cue.vy=dy/d*1.55f
        }
        playerTurn=true; message="你的回合"; postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cue=balls.firstOrNull{it.number==0 && !it.sunk} ?: return true
        val cx=toX(cue.x); val cy=toY(cue.y)
        when(event.action) {
            MotionEvent.ACTION_DOWN -> if(playerTurn && !isMoving()) {
                charging=true
                val dx=(event.x-cx)/table.width()
                val dy=(event.y-cy)/table.height()
                if(hypot(dx,dy)>.012f) { aimX=dx; aimY=dy }
                power=(hypot(event.x-cx,event.y-cy)/(table.width()*.52f)).coerceIn(.08f,1f)
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> if(charging) {
                // Finger position on the table defines the exact aim point; radial distance controls power.
                val dx=(event.x-cx)/table.width()
                val dy=(event.y-cy)/table.height()
                if(hypot(dx,dy)>.012f) { aimX=dx; aimY=dy }
                power=(hypot(event.x-cx,event.y-cy)/(table.width()*.52f)).coerceIn(.08f,1f)
                invalidate()
            }
            MotionEvent.ACTION_UP -> if(charging) {
                charging=false; shoot(aimX,aimY); invalidate(); return true
            }
            MotionEvent.ACTION_CANCEL -> { charging=false; invalidate(); return true }
        }
        return true
    }
}
