package com.itheima;

import cn.hutool.core.map.MapUtil;
import org.apache.commons.collections.MapUtils;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 快排
 */
public class QuickSort {
    public static void main(String[] args) throws InterruptedException {
        HashMap<String, Integer> map = MapUtil.newHashMap();
        String s = "abcabcbb";
        int max = 0;
        int l =0;
        for(int r = 0;r<s.length() ;r++){
            String a = String.valueOf(s.charAt(r));
            if(map.containsKey(a)){
                Integer b = MapUtils.getInteger(map, a);
                if(l<=b){
                    l = b+1;
                }
            }
            map.put(a, r);
            max = Math.max(max, r-l+1);
        }
        System.out.println(max);
    }
}