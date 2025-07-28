package com.itheima;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class JsonTraversal {
    public static void main(String[] args) throws JSONException {
        String jsonStr = "{ \"data\": [ { \"id\": 1, \"name\": \"王洋\", \"children\": [ { \"id\": 172, \"name\": \"喻明晶\", \"children\": null }, { \"id\": 147, \"name\": \"杨智鹏\"," +
                " \"children\": [ { \"id\": 95, \"name\": \"田瑞杰\", \"children\": [ { \"id\": 74, \"name\": \"李月\", \"children\": null } ] }, { \"id\": 178," +
                " \"name\": \"樊洁莉\", \"children\": [ { \"id\": 157, \"name\": \"孙勇\", \"children\": [] } ] } ] }, { \"id\": 90, \"name\": \"娄晨逸\"," +
                " \"children\": [ { \"id\": 62, \"name\": \"左镇武\", \"children\": null } ] } ] }, { \"id\": 67, \"name\": \"李鑫林\", \"children\": null } ] }";

        JSONObject jsonObj = new JSONObject(jsonStr);
        JSONArray data = jsonObj.getJSONArray("data");

        printNames(data);
    }

    public static void printNames(JSONArray array) throws JSONException {
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            String name = obj.getString("name");
            System.out.println(name);

            // 获取 children 字段，可能为 null 或 JSONArray
            JSONArray children = obj.optJSONArray("children");
            if (children != null) {
                printNames(children);
            }
        }
    }
}