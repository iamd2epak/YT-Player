package com.iamd2epak.ytplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, Lectures.all.map { it.title })
        list.setOnItemClickListener { _, _, pos, _ ->
            val lecture = Lectures.all[pos]
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_VIDEO, lecture.video)
                    .putExtra(PlayerActivity.EXTRA_TITLE, lecture.title)
            )
        }
        setContentView(list)
    }
}
