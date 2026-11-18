package com.ultimatepool.game

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

private data class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float, val number: Int, var sunk: Boolean = false) {
    val radius get() = 0.022f
    val color: Int get() = when(number) {
        0 -> Color.WHITE
        1,9 -> Color.YELLOW; 2,10 -> Color.rgb(35,85,210); 3,11 -> Color.RED
        4,12 -> Color.rgb(125,55,170); 5,13 -> Color.rgb(245,120,25)
        6,14 -> Color.rgb(30,140,65); 7,15 -> Color.rgb(125,25,35)
        else -> Color.BLACK
    }
}

class PoolGameView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
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
    private val pockets = arrayOf(floatArrayOf(0f,0f),floatArrayOf(.5f,0f),floatArrayOf(1f,0f),floatArrayOf(0f,1f),floatArrayOf(.5f,1f),floatArrayOf(1f,1f))

    init { resetRack(); isFocusable = true }

    private fun resetRack() {
        balls.clear()
        balls.add(Ball(.24f,.50f,0f,0f,0))
        val order = listOf(1,9,2,10,8,3,11,4,12,5,13,6,14,7,15)
        var k = 0
        for (row in 0..4) for (col in 0..row) {
            balls.add(Ball(.69f + row*.043f,.50f + (col-row/2f)*.047f,0f,0f,order[k++]))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        widthF = width.toFloat(); heightF = height.toFloat()
        canvas.drawColor(Color.rgb(17,22,29))
        val mx = widthF*.09f; val my = heightF*.13f
        table.set(mx,my,widthF-mx,heightF-my)
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
        paint.color=Color.rgb(220,185,115); c.drawRoundRect(table.left-18,table.top-18,table.right+18,table.bottom+18,28f,28f,paint)
        paint.color=Color.rgb(80,48,28); c.drawRoundRect(table.left-12,table.top-12,table.right+12,table.bottom+12,22f,22f,paint)
    }

    private fun drawTable(c: Canvas) {
        paint.shader=LinearGradient(table.left,table.top,table.right,table.bottom,Color.rgb(29,160,101),Color.rgb(5,91,61),Shader.TileMode.CLAMP)
        c.drawRoundRect(table,12f,12f,paint); paint.shader=null
        paint.color=Color.rgb(5,24,18); paint.style=Paint.Style.STROKE; paint.strokeWidth=2f
        c.drawRoundRect(table,12f,12f,paint); paint.style=Paint.Style.FILL
        for(p in pockets) {
            val x=table.left+p[0]*table.width(); val y=table.top+p[1]*table.height()
            paint.color=Color.rgb(4,5,7); c.drawCircle(x,y,ballPx()*1.75f,paint)
            paint.color=Color.argb(90,0,0,0); c.drawCircle(x,y,ballPx()*1.95f,paint)
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
        val lineLen=table.width()*.36f
        val ex=x+ux*lineLen; val ey=y+uy*lineLen

        // Bright directional dashed guide with a clear arrow head.
        paint.color=Color.argb(235,255,255,255); paint.strokeWidth=3.2f
        paint.pathEffect=DashPathEffect(floatArrayOf(15f,10f),0f)
        c.drawLine(x+ux*ballPx(),y+uy*ballPx(),ex,ey,paint)
        paint.pathEffect=null
        val ah=ballPx()*.78f
        val perpX=-uy; val perpY=ux
        val arrow=Path().apply {
            moveTo(ex,ey)
            lineTo(ex-ux*ah+perpX*ah*.58f,ey-uy*ah+perpY*ah*.58f)
            lineTo(ex-ux*ah-perpX*ah*.58f,ey-uy*ah-perpY*ah*.58f)
            close()
        }
        paint.color=Color.rgb(255,238,120); c.drawPath(arrow,paint)

        // Cue stick follows the opposite direction.
        val back=ballPx()*2.7f+power*table.width()*.12f
        paint.color=Color.rgb(245,190,112); paint.strokeWidth=ballPx()*.34f
        c.drawLine(x-ux*back,y-uy*back,x-ux*ballPx()*1.15f,y-uy*ballPx()*1.15f,paint)
        paint.color=Color.argb(210,255,245,220); paint.strokeWidth=2f
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
            val r=b.radius
            if(b.x<r){b.x=r;b.vx=abs(b.vx)*.88f}; if(b.x>1-r){b.x=1-r;b.vx=-abs(b.vx)*.88f}
            if(b.y<r){b.y=r;b.vy=abs(b.vy)*.88f}; if(b.y>1-r){b.y=1-r;b.vy=-abs(b.vy)*.88f}
            for(p in pockets) if(hypot(b.x-p[0],b.y-p[1])<.035f) {
                if(b.number==0){b.x=.24f;b.y=.5f;b.vx=0f;b.vy=0f;message="母球落袋：手中球"} else b.sunk=true
            }
            if(abs(b.vx)+abs(b.vy)>.001f) moving=true
        }
        for(i in active.indices) for(j in i+1 until active.size) {
            val a=active[i]; val b=active[j]; if(a.sunk||b.sunk) continue
            val dx=b.x-a.x; val dy=b.y-a.y; val d=hypot(dx,dy); val minD=a.radius+b.radius
            if(d<minD && d>.00001f) {
                val nx=dx/d; val ny=dy/d; val overlap=minD-d
                a.x-=nx*overlap*.5f; a.y-=ny*overlap*.5f; b.x+=nx*overlap*.5f; b.y+=ny*overlap*.5f
                val rel=(a.vx-b.vx)*nx+(a.vy-b.vy)*ny
                if(rel>0f){a.vx-=rel*nx*.96f;a.vy-=rel*ny*.96f;b.vx+=rel*nx*.96f;b.vy+=rel*ny*.96f}
            }
        }
        return moving
    }

    private fun isMoving()=balls.any{!it.sunk && hypot(it.vx,it.vy)>.003f}
    private fun shoot(dx:Float,dy:Float) {
        val cue=balls.firstOrNull{it.number==0 && !it.sunk} ?: return
        val len=hypot(dx,dy).coerceAtLeast(.001f)
        cue.vx=dx/len*(.65f+power*2.7f); cue.vy=dy/len*(.65f+power*2.7f)
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
                charging=true; aimX=(cx-event.x)/table.width(); aimY=(cy-event.y)/table.height(); return true
            }
            MotionEvent.ACTION_MOVE -> if(charging) {
                val dx=(cx-event.x)/table.width(); val dy=(cy-event.y)/table.height()
                val len=hypot(dx,dy).coerceAtLeast(.001f); aimX=dx/len; aimY=dy/len
                power=(hypot(event.x-cx,event.y-cy)/(table.width()*.45f)).coerceIn(.08f,1f); invalidate()
            }
            MotionEvent.ACTION_UP -> if(charging) {
                charging=false; shoot(aimX,aimY); invalidate(); return true
            }
        }
        return true
    }
}
