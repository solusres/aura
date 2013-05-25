/*
 * Copyright (C) 2013 salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */ 
(function() {
    window.WallTime || (window.WallTime = {});
    window.WallTime.data = {
        rules: {"Canada":[{"name":"Canada","_from":"1918","_to":"only","type":"-","in":"Apr","on":"14","at":"2:00","_save":"1:00","letter":"D"},{"name":"Canada","_from":"1918","_to":"only","type":"-","in":"Oct","on":"27","at":"2:00","_save":"0","letter":"S"},{"name":"Canada","_from":"1942","_to":"only","type":"-","in":"Feb","on":"9","at":"2:00","_save":"1:00","letter":"W"},{"name":"Canada","_from":"1945","_to":"only","type":"-","in":"Aug","on":"14","at":"23:00u","_save":"1:00","letter":"P"},{"name":"Canada","_from":"1945","_to":"only","type":"-","in":"Sep","on":"30","at":"2:00","_save":"0","letter":"S"},{"name":"Canada","_from":"1974","_to":"1986","type":"-","in":"Apr","on":"lastSun","at":"2:00","_save":"1:00","letter":"D"},{"name":"Canada","_from":"1974","_to":"2006","type":"-","in":"Oct","on":"lastSun","at":"2:00","_save":"0","letter":"S"},{"name":"Canada","_from":"1987","_to":"2006","type":"-","in":"Apr","on":"Sun>=1","at":"2:00","_save":"1:00","letter":"D"},{"name":"Canada","_from":"2007","_to":"max","type":"-","in":"Mar","on":"Sun>=8","at":"2:00","_save":"1:00","letter":"D"},{"name":"Canada","_from":"2007","_to":"max","type":"-","in":"Nov","on":"Sun>=1","at":"2:00","_save":"0","letter":"S"}],"Moncton":[{"name":"Moncton","_from":"1933","_to":"1935","type":"-","in":"Jun","on":"Sun>=8","at":"1:00","_save":"1:00","letter":"D"},{"name":"Moncton","_from":"1933","_to":"1935","type":"-","in":"Sep","on":"Sun>=8","at":"1:00","_save":"0","letter":"S"},{"name":"Moncton","_from":"1936","_to":"1938","type":"-","in":"Jun","on":"Sun>=1","at":"1:00","_save":"1:00","letter":"D"},{"name":"Moncton","_from":"1936","_to":"1938","type":"-","in":"Sep","on":"Sun>=1","at":"1:00","_save":"0","letter":"S"},{"name":"Moncton","_from":"1939","_to":"only","type":"-","in":"May","on":"27","at":"1:00","_save":"1:00","letter":"D"},{"name":"Moncton","_from":"1939","_to":"1941","type":"-","in":"Sep","on":"Sat>=21","at":"1:00","_save":"0","letter":"S"},{"name":"Moncton","_from":"1940","_to":"only","type":"-","in":"May","on":"19","at":"1:00","_save":"1:00","letter":"D"},{"name":"Moncton","_from":"1941","_to":"only","type":"-","in":"May","on":"4","at":"1:00","_save":"1:00","letter":"D"},{"name":"Moncton","_from":"1946","_to":"1972","type":"-","in":"Apr","on":"lastSun","at":"2:00","_save":"1:00","letter":"D"},{"name":"Moncton","_from":"1946","_to":"1956","type":"-","in":"Sep","on":"lastSun","at":"2:00","_save":"0","letter":"S"},{"name":"Moncton","_from":"1957","_to":"1972","type":"-","in":"Oct","on":"lastSun","at":"2:00","_save":"0","letter":"S"},{"name":"Moncton","_from":"1993","_to":"2006","type":"-","in":"Apr","on":"Sun>=1","at":"0:01","_save":"1:00","letter":"D"},{"name":"Moncton","_from":"1993","_to":"2006","type":"-","in":"Oct","on":"lastSun","at":"0:01","_save":"0","letter":"S"}]},
        zones: {"America/Moncton":[{"name":"America/Moncton","_offset":"-4:19:08","_rule":"-","format":"LMT","_until":"1883 Dec 9"},{"name":"America/Moncton","_offset":"-5:00","_rule":"-","format":"EST","_until":"1902 Jun 15"},{"name":"America/Moncton","_offset":"-4:00","_rule":"Canada","format":"A%sT","_until":"1933"},{"name":"America/Moncton","_offset":"-4:00","_rule":"Moncton","format":"A%sT","_until":"1942"},{"name":"America/Moncton","_offset":"-4:00","_rule":"Canada","format":"A%sT","_until":"1946"},{"name":"America/Moncton","_offset":"-4:00","_rule":"Moncton","format":"A%sT","_until":"1973"},{"name":"America/Moncton","_offset":"-4:00","_rule":"Canada","format":"A%sT","_until":"1993"},{"name":"America/Moncton","_offset":"-4:00","_rule":"Moncton","format":"A%sT","_until":"2007"},{"name":"America/Moncton","_offset":"-4:00","_rule":"Canada","format":"A%sT","_until":""}]}
    };
    window.WallTime.autoinit = true;
}).call(this);