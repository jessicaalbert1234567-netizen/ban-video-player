package com.example

import com.example.translation.BanglaNaturalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class BanglaNaturalizerTest {

    @Test
    fun testDecisionMakingCollocation() {
        val input = "আমি একটি সিদ্ধান্ত তৈরি করেছি।"
        val expected = "আমি একটি সিদ্ধান্ত নিয়েছি।"
        assertEquals(expected, BanglaNaturalizer.naturalize(input))
    }

    @Test
    fun testVerbCorrection() {
        val input = "সে সেখানে যাওয়ার সিদ্ধান্ত করেছে।"
        val expected = "সে সেখানে যাওয়ার সিদ্ধান্ত নিয়েছে।"
        assertEquals(expected, BanglaNaturalizer.naturalize(input))
    }

    @Test
    fun testAbilitySpokenRule() {
        val input = "আপনি কি আমাকে সাহায্য করতে সক্ষম?"
        val expected = "আপনি কি আমাকে সাহায্য করতে পারবেন?"
        assertEquals(expected, BanglaNaturalizer.naturalize(input))

        val input2 = "সে যেতে সক্ষম"
        val expected2 = "সে যেতে পারে"
        assertEquals(expected2, BanglaNaturalizer.naturalize(input2))
    }

    @Test
    fun testUnnecessaryCopulaRemoval() {
        val input = "এটি হচ্ছে একটি বড় সমস্যা।"
        val expected = "এটি একটি বড় সমস্যা।"
        assertEquals(expected, BanglaNaturalizer.naturalize(input))
    }

    @Test
    fun testPronounAndHonorificAgreement() {
        val input = "তিনি তার নিজের কাজ করেছে।"
        val expected = "তিনি তাঁর নিজের কাজ করেছেন।"
        assertEquals(expected, BanglaNaturalizer.naturalize(input))
    }

    @Test
    fun testAssistanceAndQuestions() {
        val q = "একটি প্রশ্ন জিজ্ঞাসা করা"
        assertEquals("প্রশ্ন করা", BanglaNaturalizer.naturalize(q))

        val a = "সাহায্য প্রদান করা"
        assertEquals("সাহায্য করা", BanglaNaturalizer.naturalize(a))

        val opp = "একটি সুযোগ প্রদান করা"
        assertEquals("সুযোগ দেওয়া", BanglaNaturalizer.naturalize(opp))
    }

    @Test
    fun testIdiomCorrection() {
        val idiom = "সে বালতিতে লাথি মেরেছে।"
        assertEquals("সে মারা গেছে।", BanglaNaturalizer.naturalize(idiom))
    }
}
