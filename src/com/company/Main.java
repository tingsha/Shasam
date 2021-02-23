package com.company;

import javax.sound.sampled.LineUnavailableException;

import com.company.GUI.*;

import java.io.IOException;

public class Main {

    public static void main(String[] args) throws LineUnavailableException, IOException {
	    /*var microphoneRecord = new getMicroRecord().GetAudioByte();
        for (byte b : microphoneRecord) {
            System.out.println(b);
        }*/
        var window = new MainWindow();
        window.createGUI();
    }
}
