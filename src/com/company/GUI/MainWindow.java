package com.company.GUI;

import com.company.Complex;
import com.company.DataPoint;
import com.company.FastFourierTransformation;
import org.tritonus.sampled.convert.PCM2PCMConversionProvider;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.*;
import java.util.*;
import java.util.List;

import static java.awt.Component.CENTER_ALIGNMENT;
import static java.awt.Component.TOP_ALIGNMENT;

/**
 * Программа написана с опорой на эти статьи:
 * https://habr.com/ru/company/wunderfund/blog/275043/
 * https://habr.com/ru/post/181654/
 */

public class MainWindow {

    public boolean running = false;
    public final int[] RANGE = new int[]{40, 80, 120, 180, 300};
    JTextField pathField = new JTextField("Linkin Park - InTheEnd.wav");
    JTextField startListeningField = new JTextField("Start listening");
    JTextField resultField = new JTextField();
    JTextPane status = new JTextPane();
    Map<Integer, Map<Integer, Integer>> matchMap = new HashMap<>();
    Map<Long, List<DataPoint>> hashMap = new HashMap<>();

    /**
     *      Часть с логикой 1:
     *      Работаем с БД
     *      TODO: в качестве БД использовать SQL/NoSQL
     */

    /**
     *      Загружаем данные из текстового файла в hashMap:
     *      id, hash, time, song name
     */
    public void loadDB() throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader("database.txt"));
        String dbLine = reader.readLine();
        List<DataPoint> listPoints;
        while (dbLine != null) {
            String[] splittedLine = dbLine.split(" +", 4);
            long songId = Integer.parseInt(splittedLine[0]);
            long h = Long.decode(splittedLine[1]);
            int t = Integer.parseInt(splittedLine[2]);
            if ((listPoints = hashMap.get(h)) == null) {
                listPoints = new ArrayList<DataPoint>();
                DataPoint point = new DataPoint((int) songId, t);
                listPoints.add(point);
                hashMap.put(h, listPoints);
            } else {
                DataPoint point = new DataPoint((int) songId, t);
                listPoints.add(point);
            }
            dbLine = reader.readLine();
        }
    }
    /**
     *
     * @return возвращаем формат, в котором обрабатывается звук
     */
    private AudioFormat getFormat() {
        float sampleRate = 44100;
        int sampleSizeInBits = 8;
        int channels = 1;          //Монофонический звук
        boolean signed = true;     //Флаг указывает на то, используются ли числа со знаком или без
        boolean bigEndian = true;  //Флаг указывает на то, следует ли использовать обратный (big-endian) или прямой (little-endian) порядок байтов
        return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
    }

    /**
     * Получаем массив байтов из файла
     * TODO: нарушение принципа Don't Repeat Yourself (listenMicroRecord)
     */
    public void listenFileRecord() throws LineUnavailableException, IOException, UnsupportedAudioFileException {
        BufferedReader names = new BufferedReader(new FileReader("song_names.txt"));
        String songName = names.readLine();
        while (songName != null) {
            if (songName.equals(pathField.getText().replace(".wav", ""))) {
                changeStatus(new Color(237, 28, 36), "song already in DB");
                return;
            }
            songName = names.readLine();
        }
        TargetDataLine lineTmp;
        AudioFormat formatTmp;
        final AudioInputStream in;
        AudioInputStream din;
        AudioInputStream outDin;
        PCM2PCMConversionProvider conversionProvider = new PCM2PCMConversionProvider();
        String filePath = pathField.getText();
        File file = new File(filePath);
        in = AudioSystem.getAudioInputStream(file);
        AudioFormat baseFormat = in.getFormat();
        AudioFormat decodedFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.getSampleRate(), 16, baseFormat.getChannels(),
                baseFormat.getChannels() * 2, baseFormat.getSampleRate(),
                false);
        din = AudioSystem.getAudioInputStream(decodedFormat, in);
        if (!conversionProvider.isConversionSupported(getFormat(),
                decodedFormat)) {
            System.out.println("Conversion is not supported");
        }
        System.out.println(decodedFormat.toString());
        outDin = conversionProvider.getAudioInputStream(getFormat(), din);
        formatTmp = decodedFormat;
        DataLine.Info info = new DataLine.Info(TargetDataLine.class,
                formatTmp);
        lineTmp = (TargetDataLine) AudioSystem.getLine(info);
        final TargetDataLine line = lineTmp;
        final AudioInputStream outDinSound = outDin;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        try {
            int count = 1;
            while (count > 0) {
                count = outDinSound.read(buffer, 0, 1024);
                if (count > 0) {
                    out.write(buffer, 0, count);
                }
            }
            out.close();
        } catch (IOException e) {
            System.err.println("I/O problems: " + e);
            System.exit(-1);
        }
        line.close();
        line.drain();
        line.stop();
        byte[] bytes = out.toByteArray();
        useFFT(bytes, false);
    }

    /**
     * быстрое преобразование Фурье
     * @param bytes - массив байтов из звуковой записи
     * @param isMicroRecord - если запись ведется с микрофона, то сразу же находим совпадения
     */
    public void useFFT(byte[] bytes, boolean isMicroRecord) throws IOException {
        final int totalSize = bytes.length;
        int amountPossible = totalSize / 4096;
        Complex[][] results = new Complex[amountPossible][];
        // Проходим по всем блокам данных и для каждого из них запускаем БПФ-анализ:
        for (int times = 0; times < amountPossible; times++) {
            Complex[] complex = new Complex[4096];
            for (int i = 0; i < 4096; i++) {
                // Помещаем данные из временной области (образцы звука) в комплексные числа с мнимой частью равной 0
                complex[i] = new Complex(bytes[(times * 4096) + i], 0);
            }
            // БПФ:
            results[times] = FastFourierTransformation.fft(complex);
        }
        if (!isMicroRecord)
            addToDB(results);
        else
            findCoincidences(results);
    }

    /**
     * В массивах лежат ключевые точки (магнитуда + частота)
     */
    public double[][] highscores = new double[3000][3000];
    public long[][] points = new long[3000][3000];

    /**
     * Формируем хэш и записываем его в БД, вместе со временем
     * @param results - комплексная матрица, полученная БПФ
     * TODO: нарушение принципа Don't Repeat Yourself (findCoincidences)
     */
    public void addToDB(Complex[][] results) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader("song_ids.txt"));
        String getLastLine = "-1";
        int lastId = -1;
        while (getLastLine != null){
            getLastLine = reader.readLine();
            if (getLastLine != null)
                lastId++;
        }
        for (int t = 0; t < results.length; t++) {
            for (int freq = 40; freq < 300; freq++) {
                // Получим силу сигнала:
                double mag = Math.log(results[t][freq].abs() + 1);
                // Выясним, в каком мы диапазоне:
                int index = getIndex(freq);
                // Сохраним самое высокое значение силы сигнала и соответствующую частоту:
                if (mag > highscores[t][index]) {
                    highscores[t][index] = mag;
                    points[t][index] = freq;
                }
            }
            long h = hash(points[t][0], points[t][1], points[t][2], points[t][3]);
            FileWriter dbWriter = new FileWriter("database.txt", true);
            dbWriter.write(String.format("%1$d %2$5d %3$10d %4$30s \n", lastId+1, h, t, pathField.getText().replace(".wav", "")));
            dbWriter.close();
        }
        FileWriter songNameWriter = new FileWriter("song_names.txt", true);
        songNameWriter.write(pathField.getText().replace(".wav", "") + "\n");
        songNameWriter.close();
        FileWriter songIdWriter = new FileWriter("song_ids.txt", true);
        songIdWriter.write((lastId + 1) + "\n");
        songIdWriter.close();
        changeStatus(new Color(34, 177, 76), "success");
        loadDB(); //Обновляем БД
        System.out.println("Finished: add to DB new song");
    }


    /**
     *  Часть с логикой 2:
     *  Работаем с щаписью микрофона
     */


    /**
     * Слушаем запись с микрофона и вызываем для нее быстрое преобразование Фурье
     */
    public void listenMicroRecord() throws LineUnavailableException, IOException {
        AudioFormat formatTmp;
        formatTmp = getFormat();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class,
                formatTmp);
        final TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        final AudioFormat format = formatTmp;
        try {
            line.open(format);
            line.start();
        } catch (LineUnavailableException e) {
            e.printStackTrace();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[(int) 1024];
        try {
            while (running) {
                int count = 0;
                count = line.read(buffer, 0, 1024);
                if (count > 0) {
                    out.write(buffer, 0, count);
                }
            }
            out.close();
            line.close();
        } catch (IOException e) {
            System.err.println("I/O problems: " + e);
            System.exit(-1);
        }
        byte[] bytes = out.toByteArray();
        useFFT(bytes, true);
    }


    /**
     * Ищем совпадения микрофонной записи с БД
     * @param results - комплексная матрица, полученная БПФ
     */
    public void findCoincidences(Complex[][] results) throws IOException {
        for (int t = 0; t < results.length; t++) {
            for (int freq = 40; freq < 300; freq++) {
                // Получим силу сигнала:
                double mag = Math.log(results[t][freq].abs() + 1);
                // Выясним, в каком мы диапазоне:
                int index = getIndex(freq);
                // Сохраним самое высокое значение силы сигнала и соответствующую частоту:
                if (mag > highscores[t][index]) {
                    highscores[t][index] = mag;
                    points[t][index] = freq;
                }
            }
            long h = hash(points[t][0], points[t][1], points[t][2], points[t][3]);

            List<DataPoint> listPoints;
            if ((listPoints = hashMap.get(h)) != null) {
                for (DataPoint dP : listPoints) {
                    int offset = Math.abs(dP.getTime() - t);
                    Map<Integer, Integer> tmpMap = null;
                    if ((tmpMap = this.matchMap.get(dP.getSongId())) == null) {
                        tmpMap = new HashMap<Integer, Integer>();
                        tmpMap.put(offset, 1);
                        matchMap.put(dP.getSongId(), tmpMap);
                    } else {
                        Integer count = tmpMap.get(offset);
                        if (count == null) {
                            tmpMap.put(offset, 1);
                        } else {
                            tmpMap.put(offset, count + 1);
                        }
                    }
                }
            }
        }

        int bestCount = 0;
        int bestSongId = -1;
        BufferedReader reader = new BufferedReader(new FileReader("song_ids.txt"));
        String getLastLine = "-1";
        int lastId = -1;
        while (getLastLine != null){
            getLastLine = reader.readLine();
            if (getLastLine != null)
                lastId++;
        }
        System.out.println(lastId);
        for (int id = 0; id <= lastId; id++) {
            System.out.println("For song id: " + id);
            Map<Integer, Integer> tmpMap = matchMap.get(id);
            int bestCountForSong = 0;
            for (Map.Entry<Integer, Integer> entry : tmpMap.entrySet()) {
                if (tmpMap == null) {
                    continue;
                }
                if (entry.getValue() > bestCountForSong) {
                    bestCountForSong = entry.getValue();
                }
                System.out.println("Time offset = " + entry.getKey()
                        + ", Count = " + entry.getValue());
            }

            if (bestCountForSong > bestCount) {
                bestCount = bestCountForSong;
                bestSongId = id;
            }
        }
        System.out.println("Best song id: " + bestSongId + " with " + bestCount + " matches");
        BufferedReader readSongNames = new BufferedReader(new FileReader("song_names.txt"));
        if (bestSongId == 0){
            String name = readSongNames.readLine();
            System.out.println(name);
            resultField.setText(name);
        }
        else if (bestSongId != -1){
            String name = "";
            for (int i = 0; i <= bestSongId; i++){
                name = readSongNames.readLine();
            }
            System.out.println("Name: " + name);
            resultField.setText(name);
        }
        else {
            System.out.println("No matches");
            resultField.setText("No matches");
        }
    }


    /**
     * Функция для определения того, в каком диапазоне находится частота
     */
    public int getIndex(int freq) {
        int i = 0;
        while (RANGE[i] < freq)
            i++;
        return i;
    }


    private static final int FUZ_FACTOR = 2;

    /**
     * Формируем хэш из 4-х точек
     */
    private long hash(long p1, long p2, long p3, long p4) {
        return (p4 - (p4 % FUZ_FACTOR)) * 100000000 + (p3 - (p3 % FUZ_FACTOR))
                * 100000 + (p2 - (p2 % FUZ_FACTOR)) * 100
                + (p1 - (p1 % FUZ_FACTOR));
    }



    /**
     *      Часть с GUI
     */

    public void createGUI() throws IOException {
        status.setBackground(null);
        changeStatus(Color.BLACK, "waiting");
        JFrame frame = new JFrame("Shazam v1.0");
        frame.setLayout(new BorderLayout());
        loadDB(); // Загружаем базу с песнями (тестовая версия - текстовый файл)
        JTextField version = new JTextField("version 1.0");
        version.setEditable(false);
        version.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));
        version.setHorizontalAlignment((int) CENTER_ALIGNMENT);
        frame.add(createPanelAddSong(), BorderLayout.NORTH);
        frame.add(createPanelStart(), BorderLayout.CENTER);
        frame.add(version, BorderLayout.SOUTH);
        frame.pack();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setLocation(screen.width / 2 - 250, (screen.height - 400) / 2 - 200);
        frame.setSize(500, 800);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    public JPanel createPanelAddSong() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        JButton addSongBtn = addSongToDBbtn();
        JTextField tip = new JTextField("Path to file (only .wav)");
        tip.setEditable(false);
        tip.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));
        tip.setHorizontalAlignment((int) CENTER_ALIGNMENT);
        c.gridx = 0;
        c.gridy = 0;
        panel.add(tip, c);
        pathField.setHorizontalAlignment((int) CENTER_ALIGNMENT);
        c.gridy = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0f;
        panel.add(pathField, c);
        c.gridy = 2;
        panel.add(addSongBtn, c);
        c.gridy = 3;
        status.setBorder(BorderFactory.createEmptyBorder());
        status.setEditable(false);
        panel.add(status, c);
        return panel;
    }

    public JPanel createPanelStart() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        startListeningField.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));
        startListeningField.setEditable(false);
        startListeningField.setFont(new Font("Comic Sans MS", Font.PLAIN, 24));
        JCheckBox startBtn = startListeningBtn();
        c.gridx = 0;
        c.gridy = 0;
        panel.add(startListeningField, c);
        c.gridy = 1;
        panel.add(startBtn, c);
        c.gridy = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0f;
        resultField.setHorizontalAlignment((int) CENTER_ALIGNMENT);
        resultField.setEditable(false);
        resultField.setBorder(BorderFactory.createEmptyBorder(40, 0, 0, 0));
        resultField.setFont(new Font("Comic Sans MS", Font.PLAIN, 14));
        panel.add(resultField, c);
        return panel;
    }

    public void changeStatus(Color color, String text) {
        status.setText("(status: ");
        StyledDocument doc = status.getStyledDocument();
        Style style = status.addStyle("Style", null);
        StyleConstants.setForeground(style, color);
        SimpleAttributeSet center = new SimpleAttributeSet();
        StyleConstants.setAlignment(center, StyleConstants.ALIGN_CENTER);
        doc.setParagraphAttributes(0, doc.getLength(), center, false);
        status.setDocument(doc);
        status.setText("(status: ");
        try {
            doc.insertString(doc.getLength(), text, style);
            StyleConstants.setForeground(style, Color.BLACK);
            doc.insertString(doc.getLength(), ")", style);
        } catch (BadLocationException ignored) {
        }
    }

    public JButton addSongToDBbtn() {
        JButton btn = new JButton("Add to DB");
        btn.setIcon(new ImageIcon());
        btn.setHorizontalAlignment((int) CENTER_ALIGNMENT);
        btn.setBorder(BorderFactory.createEmptyBorder(20, 30, 10, 30));
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setBackground(new Color(238, 238, 238));
        btn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                changeStatus(new Color(255, 201, 14), "processing");
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        running = true;
                        try {
                            listenFileRecord();
                        } catch (LineUnavailableException | UnsupportedAudioFileException | IOException lineUnavailableException) {
                            lineUnavailableException.printStackTrace();
                        }
                        btn.setForeground(Color.BLACK);
                    }
                };
                Thread thread = new Thread(r);
                thread.start();
            }
        });
        return btn;
    }

    public JCheckBox startListeningBtn() {
        JCheckBox startBtn = new JCheckBox();
        startBtn.setIcon(new ImageIcon("img/power_off.png"));
        startBtn.setBorder(BorderFactory.createEmptyBorder(0, 0, 80, 0));
        startBtn.setVerticalAlignment((int) TOP_ALIGNMENT);
        startBtn.setBackground(null);
        startBtn.setBorderPainted(false);
        startBtn.setFocusPainted(false);
        startBtn.setContentAreaFilled(false);
        startBtn.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getStateChange() == ItemEvent.SELECTED) {
                    resultField.setText("");
                    startBtn.setIcon(new ImageIcon("img/power_on.png"));
                    startListeningField.setText("Listening...");
                    startListeningField.setHorizontalAlignment((int) CENTER_ALIGNMENT);
                    startListeningField.setBorder(BorderFactory.createEmptyBorder(0, 20, 20, 0));
                    running = true;
                    Runnable r = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                listenMicroRecord();
                            } catch (LineUnavailableException | IOException lineUnavailableException) {
                                lineUnavailableException.printStackTrace();
                            }
                        }
                    };
                    Thread thread = new Thread(r);
                    thread.start();
                } else {
                    startBtn.setIcon(new ImageIcon("img/power_off.png"));
                    startListeningField.setText("Start listening");
                    startListeningField.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));
                    running = false;
                }
            }
        });
        return startBtn;
    }
}
